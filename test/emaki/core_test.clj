(ns emaki.core-test
  "Runs the wrapper against a local HTTP server that answers with the service's
  real response shapes. java -cp <clojure+spec jars>:<emaki jar>:src:test clojure.main -m emaki.core-test"
  (:require [emaki.core :as emaki]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpServer HttpHandler HttpExchange)
           (io.github.hanshs474.emaki Emaki)
           (java.net InetSocketAddress)))

(def seen (atom []))
(def answers (atom {}))

(defn- serve []
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [path (.getPath (.getRequestURI ^HttpExchange ex))
                body (slurp (.getRequestBody ^HttpExchange ex))
                _ (swap! seen conj [path body (.getFirst (.getRequestHeaders ^HttpExchange ex) "Authorization")])
                f (get @answers path)
                [status ^String out] (f)
                bytes (.getBytes out "UTF-8")]
            (.sendResponseHeaders ^HttpExchange ex (int status) (alength bytes))
            (with-open [o (.getResponseBody ^HttpExchange ex)] (.write o bytes))))))
    (.start srv)
    srv))

(defn- client-at [port key]
  (let [c (.getDeclaredConstructor Emaki (into-array Class [String String (Class/forName "io.github.hanshs474.emaki.Emaki$Transport")]))]
    (.setAccessible c true)
    (.newInstance c (object-array [key (str "http://127.0.0.1:" port) nil]))))

(defn- check [ok what] (when-not ok (throw (AssertionError. (str "FAIL " what)))))

(defn- reason [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn -main [& _]
  (let [srv (serve)
        port (.getPort (.getAddress srv))
        c (client-at port "sk-test")
        polls (atom 0)]
    (reset! answers
            {"/api/video/ai" (fn [] [200 "{\"success\":true,\"data\":{\"id\":\"vid_1\",\"status\":\"generating\",\"model\":\"seedance_2_5\"}}"])
             "/api/video/status" (fn [] (if (= 1 (swap! polls inc))
                                          [200 "{\"success\":true,\"data\":{\"id\":\"vid_1\",\"status\":\"generating\",\"videoUrl\":null}}"]
                                          [200 "{\"success\":true,\"data\":{\"id\":\"vid_1\",\"status\":\"completed\",\"videoUrl\":\"https://cdn.emaki.ai/v.mp4\",\"failureCode\":null}}"]))
             "/api/user/get-user-credits" (fn [] [200 "{\"code\":0,\"message\":\"ok\",\"data\":{\"remainingCredits\":42,\"hasPurchasedCredits\":false}}"])})
    (check (= "https://cdn.emaki.ai/v.mp4"
              (emaki/generate-video "a fox in snow" {:client c :poll-ms 0 :duration 8 :aspect-ratio "9:16" :generate-audio false}))
           "video url")
    (let [[path body auth] (first @seen)]
      (check (= path "/api/video/ai") "submit path")
      (check (= auth "Bearer sk-test") "bearer")
      (check (str/includes? body "\"model\":\"seedance_2_5\"") "default model")
      (check (str/includes? body "\"duration\":8") "duration param")
      (check (str/includes? body "\"aspect_ratio\":\"9:16\"") "hyphen to underscore")
      (check (str/includes? body "\"generate_audio\":false") "boolean param")
      (check (not (str/includes? body "poll")) "own keys stripped"))
    (check (= "{\"videoId\":\"vid_1\"}" (second (second @seen))) "status body")
    (check (= "generating" (:status (do (reset! polls 0) (emaki/status "vid_1" {:client c})))) "status map")
    (check (= 42 (emaki/credits {:client c})) "credits")
    (doseq [[st body r] [[401 "{\"error\":\"Invalid API key. Create one at https://emaki.ai/settings/apikeys\"}" :auth]
                         [402 "{\"error\":\"Insufficient credits\"}" :quota]
                         [403 "{\"error\":\"This model requires a credit purchase\"}" :plan]
                         [409 "{\"error\":\"Video generation is already in progress\"}" :busy]
                         [429 "{\"error\":\"cooldown\",\"code\":\"content_cooldown\"}" :rejected]
                         [400 "{\"error\":\"blocked\",\"code\":\"content_blocked\"}" :rejected]
                         [400 "{\"error\":\"duration must be between 4 and 30\"}" :validation]
                         [502 "{\"error\":\"upstream\"}" :other]]]
      (swap! answers assoc "/api/video/ai" (fn [] [st body]))
      (check (= r (reason #(emaki/submit "x" {:client c}))) (str st " -> " r)))
    (swap! answers assoc "/api/video/ai" (fn [] [200 "{\"success\":true,\"data\":{\"id\":\"vid_2\",\"status\":\"generating\"}}"])
                         "/api/video/status" (fn [] [200 "{\"success\":true,\"data\":{\"id\":\"vid_2\",\"status\":\"failed\",\"videoUrl\":null,\"failureCode\":\"provider_error\"}}"]))
    (check (= :failed (reason #(emaki/generate-image "x" {:client c :poll-ms 0}))) "failed run")
    (check (= :auth (reason #(emaki/submit "x" {:api-key " "}))) "empty key")
    (.stop srv 0)
    (println "all tests passed")
    (when (System/getenv "EMAKI_LIVE")
      (println "credits() ok:" (>= (emaki/credits) 0))
      (let [r (reason #(emaki/submit "a quiet harbor at dawn" {:api-key "sk-not-a-real-key" :model "nano_banana_2_lite"}))]
        (println "wrong key ->" r)
        (check (= :auth r) "live wrong key")))
    (shutdown-agents)))
