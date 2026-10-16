(ns emaki.core
  "Official SDK for emaki (https://emaki.ai), the Japanese-language AI video
  generator for Seedance 2.5 and Seedance 2.0.

    (require '[emaki.core :as emaki])
    (emaki/generate-video \"a paper lantern drifting down a night river\")
    ;; => URL of the finished MP4
    (emaki/generate-video \"a street drummer in the rain\"
                          {:model \"seedance_2_5\" :duration 8 :resolution \"720p\"})
    (emaki/generate-image \"a ramen stall in the rain, neon reflections\")
    (emaki/credits)

  Pass :api-key or set EMAKI_API_KEY; create a key at
  https://emaki.ai/settings/apikeys. Every run costs credits on your emaki
  account, the same price as on the website.

  Options: :model, :api-key, :client (a configured
  io.github.hanshs474.emaki.Emaki), :timeout-ms (default 15 minutes),
  :poll-ms (default 5000). Every other key is sent as a model parameter,
  with hyphens turned into underscores (:aspect-ratio -> aspect_ratio).

  Failures throw ex-info whose data carries :reason, one of :auth, :quota,
  :plan, :busy, :rejected, :validation, :failed, :timeout or :other."
  (:require [clojure.string :as str])
  (:import (io.github.hanshs474.emaki Emaki Emaki$Status EmakiException)
           (java.time Duration)
           (java.util LinkedHashMap Map)))

(def ^:private own-keys #{:model :api-key :client :timeout-ms :poll-ms})

(defn- client ^Emaki [{:keys [api-key client timeout-ms poll-ms]}]
  (let [^Emaki c (or client (Emaki. ^String (or api-key (System/getenv "EMAKI_API_KEY") "")))]
    (when timeout-ms (.timeout c (Duration/ofMillis timeout-ms)))
    (when poll-ms (.pollEvery c (Duration/ofMillis poll-ms)))
    c))

(defn- ->param [v]
  (cond (keyword? v) (name v)
        (map? v) (let [m (LinkedHashMap.)] (doseq [[k x] v] (.put m (str/replace (name k) "-" "_") (->param x))) m)
        (sequential? v) (mapv ->param v)
        :else v))

(defn- params ^Map [opts]
  (->param (into {} (remove (comp own-keys key)) opts)))

(defn- rethrow [^EmakiException e]
  (throw (ex-info (.getMessage e)
                  {:reason (keyword (str/lower-case (str (.kind e))))
                   :http-status (.httpStatus e)
                   :code (.code e)
                   :failure-code (.failureCode e)}
                  e)))

(defmacro ^:private guard [& body]
  `(try ~@body (catch EmakiException e# (rethrow e#))))

(defn generate-video
  "Generates one clip and returns its URL. Default model \"seedance_2_5\"
  (5 seconds, 480p, 16:9, with sound). Parameters: :duration, :resolution,
  :aspect-ratio, :generate-audio, :image (first frame, a public https URL),
  :last-frame-image."
  ([prompt] (generate-video prompt {}))
  ([prompt opts]
   (guard (.generateVideo (client opts) prompt (or (:model opts) Emaki/MODEL_VIDEO) (params opts)))))

(defn generate-image
  "Generates one image and returns its URL. Default model
  \"nano_banana_2_lite\", the cheapest run on the site. Parameters:
  :aspect-ratio, and :resolution or :quality where the model has them."
  ([prompt] (generate-image prompt {}))
  ([prompt opts]
   (guard (.generateImage (client opts) prompt (or (:model opts) Emaki/MODEL_IMAGE) (params opts)))))

(defn submit
  "Starts a run without waiting and returns its id; follow it with `status`.
  Default model \"seedance_2_5\"."
  ([prompt] (submit prompt {}))
  ([prompt opts]
   (guard (.submit (client opts) prompt (or (:model opts) Emaki/MODEL_VIDEO) (params opts)))))

(defn status
  "Where a run stands: {:id :status (\"generating\" \"completed\" \"failed\")
  :video-url :failure-code :raw}."
  ([id] (status id {}))
  ([id opts]
   (guard (let [^Emaki$Status s (.status (client opts) id)]
            {:id (.id s) :status (.status s) :video-url (.videoUrl s)
             :failure-code (.failureCode s) :raw (into {} (.raw s))}))))

(defn credits
  "The credits left on the account. Free to call."
  ([] (credits {}))
  ([opts] (guard (.credits (client opts)))))
