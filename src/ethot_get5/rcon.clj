(ns ethot-get5.rcon
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-rcon.core :as rcon])
  (:gen-class))

(def state (atom {:conns {}}))

(defn server-available?
  "Takes a get5 server DB row and runs the get5_web_available
   rcon command on the server and returns the output"
  [{:keys [server_id ip_string port rcon_password]}]
  ; TODO: This is a really lazy try/catch. Try to make it more specific.
  (try
    (when (not (contains? (:conns @state) server_id))
      (swap! state assoc-in [:conns server_id] (rcon/connect ip_string port rcon_password)))
    (=
      (get
        (json/read-str
          (first
            (str/split
              @(rcon/exec @(get-in @state [:conns server_id])
                          "get5_web_available")
              #"\n")))
        "gamestate") 0)
    (catch Exception e false)))