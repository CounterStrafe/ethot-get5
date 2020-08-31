(ns ethot_get5.db-test
  (:require [clojure.test :refer :all]
            [config.core :refer [env]]
            [libpython-clj.python :as py]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ethot_get5.db :refer :all]))

(def python-executable (:python-executable env))
(def python-library-path (:python-library-path env))
(py/initialize! :python-executable python-executable
                :library-path python-library-path)

(deftest team-test
  (let [team {"id" "1234"
              "name" "Test Team"
              "custom_fields"
                {"tag" "TT"
                 "flag" "US"}
              "lineup"
                [{"custom_fields"
                    {"steam_id" "STEAM_0:0:10885595"}}
                 {"custom_fields"
                    {"steam_id" "STEAM_0:0:46862"}}]}
        auths [(byte 0x80)
               (byte 0x03)]]
    (testing "Tear Down."
      (jdbc/execute-one! ethot-ds ["delete from team where
                                    toornament_id = ?" (get team "id")]
                         {:builder-fn rs/as-unqualified-lower-maps})
      (jdbc/execute-one! get5-web-ds ["delete from team where
                                       name = ?" (get team "name")]
                         {:builder-fn rs/as-unqualified-lower-maps}))
    (testing "import-team"
      (import-team team)
      (let [ethot-team (jdbc/execute-one! ethot-ds ["select * from team where
                                                     toornament_id = ?" (get team "id")]
                                          {:builder-fn rs/as-unqualified-lower-maps})
            get5-team (jdbc/execute-one! get5-web-ds ["select * from team where
                                                       name = ?" (get team "name")]
                                         {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= (:toornament_id ethot-team) (get team "id")))
        (is (= (:get5_id ethot-team) (:user_id get5-team)))
        (is (= (:name get5-team) (get team "name")))
        (is (= (:tag get5-team) (get-in team ["custom_fields" "tag"])))
        (is (= (:flag get5-team) (get-in team ["custom_fields" "flag"])))
        (is (= (:auths get5-team)))))))
