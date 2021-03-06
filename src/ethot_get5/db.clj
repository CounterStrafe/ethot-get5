(ns ethot-get5.db
  (:require [clojure.string :as str]
            [config.core :refer [env]]
            [libpython-clj.python :refer [py. py.. py.-] :as py]
            [libpython-clj.require :refer [require-python]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:gen-class))

(require-python '[builtins :as python])
(require-python 'pickle)

(def db-host (:mysql-host env))
(def db-user (:mysql-user env))
(def db-password (:mysql-pass env))
(def import-blacklist (:import-blacklist env))
(def map-pool (:map-pool env))
(def python-executable (:python-executable env))

(def ethot-ds (jdbc/get-datasource
               {:dbtype "mysql"
                :dbname "ethot"
                :host db-host
                :user db-user
                :password db-password}))

(def get5-web-ds (jdbc/get-datasource
                  {:dbtype "mysql"
                   :dbname "get5-web"
                   :host db-host
                   :user db-user
                   :password db-password}))

(defn pickle-steam-ids
  "Takes a vector of Steam ID's, pickles it as a Python list,
   and returns the pickled object as a byte-array"
  [steam-ids]
  (let [plist (py/->py-list steam-ids)
        pbytes (pickle/dumps plist :protocol 2)
        pbytes-len (python/len pbytes)
        cljbytes (byte-array pbytes-len)]
    (doseq [i (range pbytes-len)]
      (aset-byte cljbytes i (unchecked-byte (py. pbytes __getitem__ i))))
    cljbytes))

(defn import-team
  "Takes a Toornament participant entry
   and adds it to the get5-web and ethot team tables"
  [team]
  (let [team-name (get team "name")
        team-tag (get-in team ["custom_fields" "tag"])
        auths (pickle-steam-ids (map #(get-in % ["custom_fields" "steam_id"]) (get team "lineup")))
        get5-id (:GENERATED_KEY (jdbc/execute-one! get5-web-ds ["insert into team (name,
                                                                                   tag,
                                                                                   flag,
                                                                                   auths,
                                                                                   public_team)
                                                                 values (?, ?, ?, ?, ?)"
                                                                team-name team-tag "" auths 0]
                                                   {:return-keys true}))]

    (jdbc/execute-one! ethot-ds ["insert into team (toornament_id, get5_id)
                                  values (?, ?)"
                                 (get team "id") get5-id]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn team-imported?
  [{:strs [id]}]
  (not
    (nil?
      (jdbc/execute-one! ethot-ds ["select * from team
                                    where toornament_id = ?" id]
                         {:builder-fn rs/as-unqualified-lower-maps}))))

(defn import-server
  [{:keys [id ip_string port rcon_password]}]
  (jdbc/execute-one! get5-web-ds ["insert into game_server (id,
                                                            ip_string,
                                                            port,
                                                            rcon_password,
                                                            public_server)
                                   values (?, ?, ?, ?, ?)"
                                  id ip_string port rcon_password 0]
                     {:return-keys true}))

(defn server-imported?
  [{:keys [id]}]
  (not
    (nil?
      (jdbc/execute-one! get5-web-ds ["select * from game_server
                                       where id = ?" id]
                         {:builder-fn rs/as-unqualified-lower-maps}))))

(defn toornament-to-get5-team-id
  "Takes a Toornament participant ID
   and returns it's ID in the get5-web team table"
  [toornament-id]
  (:get5_id (jdbc/execute-one! ethot-ds ["select get5_id from team
                                          where toornament_id = ?"
                                         toornament-id]
                               {:builder-fn rs/as-unqualified-lower-maps})))

(defn gen-api-key
  "Returns a randomly generated 24-character alphanumeric string."
  []
  (clojure.string/join (take 24 (repeatedly #(get "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" (rand-int 36))))))

(defn team-name
  [team-id]
  (:name
    (jdbc/execute-one! get5-web-ds ["select name from team
                                     where id = ?"
                                    team-id]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn create-user
  "Creates an admin user in get5-web and returns its ID."
  []
  (:GENERATED_KEY (jdbc/execute-one! get5-web-ds ["insert into user (name, admin)
                                                   values (?, ?)"
                                                  "ethot-get5" true]
                                     {:return-keys true})))

(defn import-match
  "Takes a Toornament match, the max number of maps to be played,
   and a get5 server DB row. Adds the match to the get5-web and ethot match tables,
   and sets the server in_use column in the get5 game_server table."
  [match user-id max-maps server]
  (let [server-id (:id server)
        plugin-version (:plugin_version server)
        team1-toornament-id (get-in match ["opponents" 0 "participant" "id"])
        team2-toornament-id (get-in match ["opponents" 1 "participant" "id"])
        team1-id (toornament-to-get5-team-id team1-toornament-id)
        team2-id (toornament-to-get5-team-id team2-toornament-id)
        team1-name (team-name team1-id)
        team2-name (team-name team2-id)
        api-key (gen-api-key)
        get5-id (:GENERATED_KEY (jdbc/execute-one! get5-web-ds ["insert into `match` (user_id,
                                                                                      server_id,
                                                                                      team1_id,
                                                                                      team2_id,
                                                                                      plugin_version,
                                                                                      cancelled,
                                                                                      max_maps,
                                                                                      skip_veto,
                                                                                      veto_mappool,
                                                                                      api_key,
                                                                                      team1_score,
                                                                                      team2_score,
                                                                                      team1_string,
                                                                                      team2_string)
                                                                 values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                                                user-id
                                                                server-id
                                                                team1-id
                                                                team2-id
                                                                plugin-version
                                                                false
                                                                max-maps
                                                                0
                                                                (str/join " " map-pool)
                                                                api-key
                                                                0
                                                                0
                                                                team1-name
                                                                team2-name]
                                                   {:return-keys true}))]
    (jdbc/execute-one! ethot-ds ["insert into `match` (toornament_id, get5_id)
                                  values (?, ?)"
                                 (get match "id") get5-id]
                       {:builder-fn rs/as-unqualified-lower-maps})
    (jdbc/execute-one! get5-web-ds ["update game_server
                                     set in_use = true
                                     where id = ?"
                                    server-id]
                       {:builder-fn rs/as-unqualified-lower-maps})
    [get5-id api-key]))

(defn toornament-to-get5-match-id
  "Takes a Toornament match ID
   and returns it's ID in the get5-web match table"
  [toornament-id]
  (:get5_id (jdbc/execute-one! ethot-ds ["select get5_id from `match`
                                          where toornament_id = ?"
                                         toornament-id]
                               {:builder-fn rs/as-unqualified-lower-maps})))

(defn get5-to-toornament-match-id
  "Takes a get5-web match ID
   and returns it's corresponding Toornament ID"
  [toornament-id]
  (:toornament_id (jdbc/execute-one! ethot-ds ["select toornament_id from `match`
                                                where get5_id = ?"
                                               toornament-id]
                                     {:builder-fn rs/as-unqualified-lower-maps})))

(defn get-servers-not-in-use
  "Returns the servers not in use."
  []
  (jdbc/execute! get5-web-ds ["select * from game_server
                               where in_use = false
                               or in_use is null
                               order by id asc"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn match-imported?
  "Takes a Toornament match ID and returns whether it has been imported or not."
  [toornament-id]
  (not= (:c (jdbc/execute-one! ethot-ds ["select count(*) as c
                                          from `match`
                                          where toornament_id = ?" toornament-id]
                               {:builder-fn rs/as-unqualified-lower-maps}))
        0))

(defn match-on-server?
  "Returns whether the server ID has a match running on it."
  [server-id]
  (not= (:c (jdbc/execute-one! get5-web-ds ["select count(*) as c
                                             from `match`
                                             where server_id = ? and
                                             end_time is null and
                                             (cancelled = false or cancelled is null)"
                                            server-id]
                               {:builder-fn rs/as-unqualified-lower-maps}))
     0))

(defn delay-match
  "Adds a match to the delays table."
  [match-id]
  (jdbc/execute-one! ethot-ds ["insert into delays (match_id)
                                values (?)" match-id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn match-delayed?
  "Checks if a match is delayed."
  [match-id]
  (not= (:c (jdbc/execute-one! ethot-ds ["select count(*) as c
                                          from delays
                                          where match_id = ?" match-id]
                               {:builder-fn rs/as-unqualified-lower-maps}))
        0))

(defn resume-match
  "Removes a match from the delays table."
  [match-id]
  (jdbc/execute-one! ethot-ds ["delete from delays
                                where match_id = ?" match-id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn add-unreported
  "adds a match to the reports table as unreported"
  [match-id]
  (jdbc/execute-one! ethot-ds ["insert into reports (get5_match_id, report_status)
                                values (?, ?)" match-id 0]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn set-unreported
  "Mark the match-id as unreported in the reports table"
  [match-id]
  (jdbc/execute-one! ethot-ds ["update reports
                                set report_status = 0
                                where get5_match_id = ?" match-id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn set-report-timer
  "Mark the match-id as timer started in the reports table"
  [match-id]
  (jdbc/execute-one! ethot-ds ["update reports
                                set report_status = 1
                                where get5_match_id = ?" match-id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn set-reported
  "Mark the match-id as reported in the reports table"
  [match-id]
  (jdbc/execute-one! ethot-ds ["update reports
                                set report_status = 2
                                where get5_match_id = ?" match-id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn set-exported
  "Mark the match-id as exported in the reports table"
  [match-id]
  (jdbc/execute-one! ethot-ds ["update reports
                                set report_status = 3
                                where get5_match_id = ?" match-id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn report-status-value
  [match-id]
  (:report_status
   (jdbc/execute-one! ethot-ds ["select *
                                 from reports
                                 where get5_match_id = ?" match-id]
                      {:builder-fn rs/as-unqualified-lower-maps})))

(defn report-timer-started?
  "See if the match-id is marked as timer-started in the reports table"
  [match-id]
  (> (report-status-value match-id) 0))

(defn in-timer?
  "See if the match-id is marked as timer-started in the reports table"
  [match-id]
  (= (report-status-value match-id) 1))

(defn in-reports-table?
  "See if the match-id is in the reports table"
  [match-id]
  (not=
   (:c
    (jdbc/execute-one! ethot-ds ["select count(*) as c
                                  from reports
                                  where get5_match_id = ?" match-id]
                       {:builder-fn rs/as-unqualified-lower-maps}))
   0))

(defn get-newly-ended-games
  "Retrieves the games that have recently ended give the games we know already ended
  TODO: see if query can take a list directly for the not-in"
  [exportable-identifier-ids]
  (if (empty? exportable-identifier-ids)
    '()
    (let [result (jdbc/execute! get5-web-ds
                                [(str "select id from `match` where id in ("
                                      (str/join "," exportable-identifier-ids) ") "
                                      "and winner is not null
                                       and end_time is not null")]
                                {:builder-fn rs/as-unqualified-lower-maps})]
      (map #(int (:id %)) result))))

(defn get-match-id-with-team
  [team-name]
  (let [result (jdbc/execute! get5-web-ds
                              [(str "select id from `match` where "
                                    "'" team-name "'" " "
                                    "in (team1_string, team2_string)")]
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (map :id result)))

(defn get-map-stat
  [get5-match-id map-number]
  (jdbc/execute-one! get5-web-ds ["select * from map_stats
                                   where match_id = ?
                                   and map_number = ?"
                                  get5-match-id map-number]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn get-match-result
  [get5-match-id]
  (let [match (jdbc/execute-one! get5-web-ds ["select * from `match`
                                               where id = ?" get5-match-id]
                                 {:builder-fn rs/as-unqualified-lower-maps})
        max-maps (:max_maps match)]
    (for [x (range 0 max-maps)
          :let [map-stat (get-map-stat get5-match-id x)]
          :when (some? map-stat)]
      {:game-number (inc x)
       :team1-score (:team1_score map-stat)
       :team2-score (:team2_score map-stat)})))
