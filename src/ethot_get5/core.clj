(ns ethot-get5.core
  (:require [config.core :refer [env]]
            [libpython-clj.python :as py]))

(def python-executable (:python-executable env))
(def python-library-path (:python-library-path env))
(py/initialize! :python-executable python-executable
                :library-path python-library-path)

(ns ethot-get5.core
  (:require [discljord.connections :as dconn]
            [discljord.messaging :as dmess]
            [discljord.events :as devent]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [ethot-get5.db :as db]
            [ethot-get5.rcon :as rcon]
            [ethot-get5.toornament :as toornament])
  (:gen-class))

(def state (atom {}))

(def discord-admin-channel-id (:discord-admin-channel-id env))
(def discord-announcements-channel-id (:discord-announcements-channel-id env))
(def discord-guild-id (:discord-guild-id env))
(def discord-token (:discord-token env))
(def game-server-password (:game-server-password env))
(def get5-match-config-url-template (:get5-match-config-url-template env))
(def import-blacklist (:import-blacklist env))
(def map-pool (:map-pool env))
(def report-timeout (:report-timeout env))

(defn sync-teams
  "Imports every team from the tournament."
  [tournament-id]
  (doseq [team (toornament/participants tournament-id)]
    (db/import-team team)))

(defn unimported-matches
  "Returns the matches that can and have not been imported yet."
  [tournament-id]
  (filter #(and (not (db/match-imported? (get % "id")))
                (not (db/match-delayed? (get % "id")))
                (not (contains? import-blacklist (get % "id"))))
          (toornament/importable-matches tournament-id)))

(defn get-available-server
  "Returns the next available server."
  []
  (filter #(and (not (db/match-on-server? (:id %)))
                (rcon/server-available? %))
          (db/get-servers-not-in-use)))

(defn create-match-config-url
  "Returns the config URL for the match ID."
  [match-id]
  (str/replace get5-match-config-url-template #"<match-id>" match-id))

(defn notify-discord
  "Announces the game in the announcements channel and DM's all the players with
  the server creds."
  [team1 team2 server-id]
  (let [team1-name (get team1 "name")
        team2-name (get team2 "name")]
    (dmess/create-message! (:messaging @state) discord-announcements-channel-id
                           :content (str team1-name " vs " team2-name " is now ready "
                                         "on server " server-id))))

(defn get-team-discord-usernames
  "Takes a Toornament team and returns the discord usernames in it."
  [team]
  (reduce #(if-let [dname (get-in %2 ["custom_fields" "discord_username"])]
             (conj %1 (str/trim dname))
             %1)
          '()
          (get team "lineup")))

(defn gen-discord-user-map
  "Generates a map of Discord usernames to their ID's."
  []
  (reduce (fn [user-map user]
            (assoc user-map
                   (str (get-in user [:user :username]) "#" (get-in user [:user :discriminator]))
                   (get-in user [:user :id])))
          {}
          @(dmess/list-guild-members! (:messaging @state)
                                      discord-guild-id
                                      :limit 1000)))

(defn get-discord-user-id
  "Takes a Discord username and returns it's Discord ID."
  [discord-username]
  (when (not (contains? (:discord-user-ids @state) discord-username))
    (swap! state assoc :discord-user-ids (gen-discord-user-map)))
  (get (:discord-user-ids @state) discord-username))

(defn notify-players
  [team1 team2 ip]
  (let [teams (list team1 team2)
        team1-name (get (first teams) "name")
        team2-name (get (second teams) "name")
        discord-usernames (flatten (map get-team-discord-usernames teams))
        discord-user-ids (map get-discord-user-id discord-usernames)]
    (doseq [discord-id discord-user-ids]
      (let [channel-id (:id @(dmess/create-dm! (:messaging @state) discord-id))]
        (dmess/create-message! (:messaging @state) channel-id
                               :content (str team1-name " vs " team2-name " is now ready!"
                                             ; For some reason using steam:// links
                                             ; will give you a "Server Full" error
                                             ; even when it's not.
                                             ;"\nsteam://connect/" ip "/" config_password
                                             "\n" "`connect " ip
                                             "; password " game-server-password ";`"))))))

(defn export-game
  [tournament-id get5-match-id]
  (let [toornament-match-id (db/get5-to-toornament-match-id get5-match-id)
        match-result (db/get-match-result)]
    (doseq [game-result match-result]
      (toornament/complete-game tournament-id
                                toornament-match-id
                                (:game-number game-result)
                                (:team1-score game-result)
                                (:team2-score game-result)))))

(defn await-game-status
  "waits for the channel to recieve a map of inforamiotn about the caller
  or nil from timeout and will the find the game to delay exporting and make sure"
  [tournament-id id time-to-wait chan]
  (println (str "testing chan passed to await-game-status" chan))
  (async/go
    (async/alt!
      (async/timeout time-to-wait) ([x]
                                    (export-game tournament-id id)
                                    (db/set-exported id)
                                    (swap! state update-in [:games-awaiting-close] dissoc (str id)))
      chan ([x]
            (db/set-reported id)
            (swap! state update-in [:games-awaiting-close] dissoc (str id))))))

(defn export-games
  "Will find new games that have recently ended and create a new channel that
  will be notified when either the 5min timeout is reached to allow the next
  game in the bracket to be started OR a player reported suspicious activity
  and will stop the starting of the next game until manually restarted by a TO"
  [state tournament-id]
  (let [{:keys [games-awaiting-close close-game-time]} state
        ready-games (toornament/importable-matches tournament-id)
        identifier-ids (map #(db/toornament-to-get5-match-id (get % "id")) ready-games)
        recently-ended (db/get-newly-ended-games identifier-ids)]
    (doseq [get5-id (filter #(and (contains? games-awaiting-close (str %))
                                      (not (db/report-timer-started? %))) recently-ended)]
      (db/set-report-timer get5-id)
      (await-game-status tournament-id
                         get5-id
                         close-game-time
                         (get-in state [:games-awaiting-close (str get5-id)])))))

(defn run-stage
  "Continuously imports and exports all available games every 30 seconds."
  [tournament-id stage-name]
  (async/go
    (sync-teams)
    (let [stage-id (get (toornament/get-stage tournament-id stage-name) "id")]
      (swap! state assoc :tournament-id tournament-id)
      (loop []
        (println "Running")
        (doseq [match (unimported-matches tournament-id)]
          (let [match-id (get match "id")
                games (toornament/games tournament-id match-id)
                server (get-available-server)
                get5-match-id (db/import-match match (count games) server)
                match-config-url (create-match-config-url get5-match-id)
                team1-id (get-in match ["opponents" 0 "participant" "id"])
                team2-id (get-in match ["opponents" 1 "participant" "id"])
                team1 (toornament/participant tournament-id team1-id)
                team2 (toornament/participant tournament-id team2-id)]
            (rcon/send-to-server match-config-url server)
            (notify-discord team1 team2 (:id server))
            (notify-players team1 team2 (:ip_string server))
            (when (not (contains? (:games-awaiting-close @state) get5-match-id))
              (swap! state assoc-in [:games-awaiting-close get5-match-id] (async/chan))
              (cond
                (not (db/in-reports-table? get5-match-id))
                (db/add-unreported get5-match-id)

                ;; we will reset the timer, since the game will
                ;; still be exportable on through toornament state
                (db/in-timer? get5-match-id)
                (db/set-unreported get5-match-id)))))

        ; exports here
        (export-games @state tournament-id)
        (async/<! (async/timeout 30000))
        (if (or (not (:stage-running @state))
                (toornament/stage-complete? tournament-id stage-id))
          (println "Stopping")
          (recur))))))

(defmulti handle-event
  (fn [event-type event-data]
    (when (and
           (not (:bot (:author event-data)))
           (= event-type :message-create))
      (first (str/split (:content event-data) #" ")))))

(defmethod handle-event "!run-stage"
  [event-type {:keys [content channel-id]}]
  (when (= channel-id discord-admin-channel-id)
    (let [[tournament-id stage-name] (str/split (str/replace content #"!run-stage " "") #" ")]
      (println "Received run")
      (if (:stage-running @state)
        (dmess/create-message! (:messaging @state) channel-id
                               :content "A stage is already running.")
        (do
          (swap! state assoc :stage-running true)
          (run-stage tournament-id stage-name))))))

(defn -main
  [& args]
  (let [event-ch (async/chan 100)
        connection-ch (dconn/connect-bot! discord-token event-ch)
        messaging-ch (dmess/start-connection! discord-token)
        init-state {:connection connection-ch
                    :event event-ch
                    :messaging messaging-ch
                    :stage-running false
                    :discord-user-ids {}
                    :games-awaiting-close {}
                    :close-game-time report-timeout}]
    (reset! state init-state)
    (devent/message-pump! event-ch handle-event)
    (dmess/stop-connection! messaging-ch)
    (dconn/disconnect-bot! connection-ch)))
