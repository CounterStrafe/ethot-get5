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
            [ethot-get5.toornament :as toornament])
  (:gen-class))

(def state (atom {}))

(def discord-admin-channel-id (:discord-admin-channel-id env))
(def discord-announcements-channel-id (:discord-announcements-channel-id env))
(def discord-guild-id (:discord-guild-id env))
(def discord-token (:discord-token env))
(def game-server-password (:game-server-password env))
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
  []
  (let [servers (db/get-servers-not-in-use)]))

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
                ;[server-id plugin-version] (get-available-server)
                ]
            ;(db/import-match match (count games) server-id plugin-version)
            ; RCON send_to_server
          ))))))

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
