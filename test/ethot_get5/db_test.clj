(ns ethot_get5.db-test
  (:require [config.core :refer [env]]
            [libpython-clj.python :refer [py. py.. py.-] :as py]))

(def python-executable (:python-executable env))
(def python-library-path (:python-library-path env))

(py/initialize! :python-executable python-executable
                :library-path python-library-path)

(ns ethot_get5.db-test
  (:require [clojure.test :refer :all]
            [libpython-clj.require :refer [require-python]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ethot-get5.db :refer :all]))

(require-python '[builtins :as python])
(require-python 'pickle)

(defn unpickle-steam-ids
  [cljbytes]
  (let [pbytes (python/bytearray)
        cljbytes-len (count cljbytes)]
    (doseq [i (range cljbytes-len)]
      (py. pbytes append (+ (get cljbytes i) 128)))
    (pickle/loads (python/bytes pbytes))))

(deftest pickle-test
  (testing "pickle"
    (is (= (unpickle-steam-ids (pickle-steam-ids ["hi", "ethot"])) ["hi", "ethot"]))))

(deftest import-test
  (let [team1 {"id" "1111"
               "name" "Test Team 1"
               "custom_fields"
               {"tag" "TT1"
                "flag" "US"}
               "lineup"
               [{"custom_fields"
                 {"steam_id" "STEAM_0:0:10885595"}}
                {"custom_fields"
                 {"steam_id" "STEAM_0:0:46862"}}]}
        team2 {"id" "2222"
               "name" "Test Team 2"
               "custom_fields"
               {"tag" "TT2"
                "flag" "US"}
               "lineup"
               [{"custom_fields"
                 {"steam_id" "STEAM_0:1:12147600"}}
                {"custom_fields"
                 {"steam_id" "STEAM_0:0:13112496"}}]}
        steam-ids (map #(get-in % ["custom_fields" "steam_id"]) (get team1 "lineup"))
        match {"id" "3333"
               "opponents"
               [{"participant"
                 {"id" "1111"}}
                {"participant"
                 {"id" "2222"}}]}
        server-ip "test-server"
        plugin-version "0.7.1"]

    ; Do tear-down first so we can clean up after failed tests.
    (testing "Tear Down."
      ; Clear match tables
      (jdbc/execute-one! get5-web-ds ["delete from `match` where
                                       team1_id = ?"
                                      (toornament-to-get5-team-id (get team1 "id"))]
                         {:builder-fn rs/as-unqualified-lower-maps})
      (jdbc/execute-one! ethot-ds ["delete from `match` where
                                    toornament_id = ?" (get match "id")]
                         {:builder-fn rs/as-unqualified-lower-maps})
      ; Clear team tables
      (jdbc/execute-one! ethot-ds ["delete from team
                                    where toornament_id = ?
                                    or toornament_id = ?"
                                   (get team1 "id") (get team2 "id")]
                         {:builder-fn rs/as-unqualified-lower-maps})
      (jdbc/execute-one! get5-web-ds ["delete from team
                                       where name = ?
                                       or name = ?"
                                      (get team1 "name") (get team2 "name")]
                         {:builder-fn rs/as-unqualified-lower-maps})
      ; Clear game_server table
      (jdbc/execute-one! get5-web-ds ["delete from game_server where
                                       ip_string = ?" server-ip]
                         {:builder-fn rs/as-unqualified-lower-maps}))

    (testing "import-team"
      (import-team team1)
      (import-team team2)
      (let [ethot-team (jdbc/execute-one! ethot-ds ["select * from team
                                                     where toornament_id = ?"
                                                    (get team1 "id")]
                                          {:builder-fn rs/as-unqualified-lower-maps})
            get5-team (jdbc/execute-one! get5-web-ds ["select * from team
                                                       where name = ?"
                                                      (get team1 "name")]
                                         {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= (:toornament_id ethot-team) (get team1 "id")))
        (is (= (:get5_id ethot-team) (:id get5-team)))
        (is (= (:name get5-team) (get team1 "name")))
        (is (= (:tag get5-team) (get-in team1 ["custom_fields" "tag"])))
        (is (= (:flag get5-team) (get-in team1 ["custom_fields" "flag"])))
        (is (= (unpickle-steam-ids (:auths get5-team)) steam-ids))
        (is (= (toornament-to-get5-team-id (get team1 "id")) (:id get5-team)))))

    (testing "import-match"
      (let [server-id (:GENERATED_KEY (jdbc/execute-one! get5-web-ds ["insert into
                                                                       game_server (ip_string)
                                                                       values (?)" server-ip]
                                                         {:return-keys true}))
            max-maps 1]
        (import-match match max-maps {:server_id server-id :plugin_version plugin-version})
        (let [ethot-match (jdbc/execute-one! ethot-ds ["select * from `match`
                                                        where toornament_id = ?"
                                                       (get match "id")]
                                             {:builder-fn rs/as-unqualified-lower-maps})
              get5-match (jdbc/execute-one! get5-web-ds ["select * from `match`
                                                          where id = ?"
                                                         (:get5_id ethot-match)]
                                            {:builder-fn rs/as-unqualified-lower-maps})
              game-server (jdbc/execute-one! get5-web-ds ["select * from game_server
                                                          where id = ?"
                                                          server-id]
                                             {:builder-fn rs/as-unqualified-lower-maps})
              team1-get5-id (toornament-to-get5-team-id (get-in match ["opponents" 0 "participant" "id"]))
              team2-get5-id (toornament-to-get5-team-id (get-in match ["opponents" 1 "participant" "id"]))]
          (is (= (:toornament_id ethot-match) (get match "id")))
          (is (= (:get5_id ethot-match) (:id get5-match)))
          (is (= (:server_id get5-match) server-id))
          (is (= (:team1_id get5-match) team1-get5-id))
          (is (= (:team2_id get5-match) team2-get5-id))
          (is (= (:max_maps get5-match) max-maps))
          (is (= (:skip_veto get5-match) true))
          (is (= (count (:api_key get5-match)) 24))
          (is (= (:plugin_version get5-match) plugin-version))
          (is (= (:in_use game-server) true)))))

    (testing "match-on-server?"
      (let [server-id (:id (jdbc/execute-one! get5-web-ds ["select id from game_server
                                                            where ip_string = ?" server-ip]
                                              {:builder-fn rs/as-unqualified-lower-maps}))]
        (is (= (match-on-server? server-id) true))
        (is (= (match-on-server? 9999) false))))

    (testing "get-servers-not-in-use"
      (let [server-id (:id (jdbc/execute-one! get5-web-ds ["select id from game_server
                                                            where ip_string = ?" server-ip]
                                              {:builder-fn rs/as-unqualified-lower-maps}))]
        (jdbc/execute-one! get5-web-ds ["update game_server
                                       set in_use = false
                                       where ip_string = ?" server-ip]
                           {:builder-fn rs/as-unqualified-lower-maps})
        (is (= (count (get-servers-not-in-use)) 1))
        (is (= (get-in (get-servers-not-in-use) [0 :id]) server-id))
        (jdbc/execute-one! get5-web-ds ["update game_server
                                       set in_use = true
                                       where ip_string = ?" server-ip]
                           {:builder-fn rs/as-unqualified-lower-maps})
        (is (= (count (get-servers-not-in-use)) 0))))))