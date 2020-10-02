(ns ethot_get5.rcon-test
  (:require [clojure.test :refer :all]
            [config.core :refer [env]]
            [ethot-get5.rcon :refer :all]))

(def test-server-host (:test-server-host env))
(def test-server-port (:test-server-port env))
(def test-server-rcon-password (:test-server-rcon-password env))

(deftest rcon-test
  (testing "server-available? and send-to-server"
    (let [server {:id 1
                  :ip_string test-server-host
                  :port test-server-port
                  :rcon_password test-server-rcon-password}
          bad-server {:id 2
                      :ip_string "notahost"
                      :port 11111
                      :rcon_password "hi"}
          api-key "test-key"]
      (is (server-available? server))
      (is (not (server-available? bad-server)))
      (is (.contains (send-to-server "notaurl" api-key server) "command \"get5_loadmatch_url notaurl\"")))))