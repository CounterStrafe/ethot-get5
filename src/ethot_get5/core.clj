(ns ethot.core
  (:require [discljord.connections :as dconn]
            [discljord.messaging :as dmess]
            [discljord.events :as devent]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [config.core :refer [env]]
            [ethot.db :as db]
            [ethot.toornament :as toornament])
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

(defn add-teams
  [tournament-id]
  (doseq [team (toornament/participants tournament-id)]
    (db/add-team team)))

(defn run-stage
  "Continuously imports and exports all available games every 30 seconds."
  [tournament-id stage-name]
  (async/go
    (let [stage-id (get (toornament/get-stage tournament-id stage-name) "id")]
      (swap! state assoc :tournament-id tournament-id)
      (loop []
        (println "Running")
        (doseq [match (unimported-matches tournament-id)]
          (let [match-id (get match "id")
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; Currently we only support single-game matches
                game (first (toornament/games tournament-id match-id))
                game-number (get game "number")
                ebot-match-id (ebot/import-game tournament-id match-id game-number)
                team1-id (get-in match ["opponents" 0 "participant" "id"])
                team2-id (get-in match ["opponents" 1 "participant" "id"])
                team1 (toornament/participant tournament-id team1-id)
                team2 (toornament/participant tournament-id team2-id)
                ; This code assumes there are more available servers than games
                ; that can be played at one time. There is no logic for
                ; prioritising games earlier in the bracket.
                server-id (ebot/get-available-server)]
            (ebot/set-match-password ebot-match-id game-server-password)
            (ebot/assign-server server-id ebot-match-id)
            (notify-discord tournament-id team1 team2 server-id)
            (start-veto tournament-id match-id ebot-match-id server-id team1 team2)
            (when (not (contains? (:games-awaiting-close @state) ebot-match-id))
              (swap! state assoc-in [:games-awaiting-close ebot-match-id] (async/chan))
              (cond
                (not (db/in-reports-table? ebot-match-id))
                (db/add-unreported ebot-match-id)

                ;; we will reset the timer, since the game will
                ;; still be exportable on through toornament state
                (db/in-timer? ebot-match-id)
                (db/set-unreported ebot-match-id)))))

        ; exports here
        (export-games @state tournament-id)
        (async/<! (async/timeout 30000))
        (if (or (not (:stage-running @state))
                (toornament/stage-complete? tournament-id stage-id))
          (println "Stopping")
          (recur))))))

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
