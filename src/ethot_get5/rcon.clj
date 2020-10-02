(ns ethot-get5.rcon
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-rcon.core :as rcon])
  (:gen-class))

(def state (atom {:conns {}}))

(defn- exec
  "Executes an RCON command on a server"
  [command {:keys [id ip_string port rcon_password]}]
  (when (not (contains? (:conns @state) id))
    (swap! state assoc-in [:conns id] (rcon/connect ip_string port rcon_password)))
  @(rcon/exec @(get-in @state [:conns id]) command))

(defn server-available?
  "Takes a get5 server DB row and runs the get5_web_available
   RCON command on the server and returns the output"
  [server]
  ; TODO: This is a really lazy try/catch. Try to make it more specific.
  (try
    (=
      (get
        (json/read-str
          (first
            (str/split
              (exec "get5_web_available" server)
              #"\n")))
        "gamestate") 0)
    (catch Exception e false)))

(defn send-to-server
  "Executes the get5_loadmatch_url RCON command on the server
   with the match config URL"
  [match-config-url match-api-key server]
  (let [resp (exec (str "get5_loadmatch_url " match-config-url) server)]
    (exec (str "get5_web_api_key " match-api-key) server)
    resp))