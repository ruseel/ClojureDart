(ns cljd.reader
  (:require ["dart:io" :as io]
            ["dart:async" :as async]))

;; ReaderInput class definition
(deftype ReaderInput [^#/(async/Stream String) in
                      ^:mutable ^#/(async/StreamSubscription? String) subscription
                      ^:mutable ^#/(async/Completer? String?) completer
                      ^:mutable ^String? buffer]
  (^void init_stream_subscription [this]
   (set! subscription
     (doto (.listen in
             (fn [^String s]
               (assert (nil? buffer))
               (when-not (== "" s)
                 (.complete completer s)
                 (.pause subscription))) .&
             :onDone
             (fn []
               (assert (nil? buffer))
               (.complete completer nil)
               (set! subscription nil)
               nil))
       (.pause)))
   nil)
  (^#/(async/FutureOr String?) read [this]
   (when-not (nil? subscription)
     (if-some [buf buffer]
       (do (set! buffer nil)
           (async.Future/value buf))
       (do (set! completer (async/Completer.))
           (.resume subscription)
           (.-future completer)))))
  (^void unread [this ^String s]
   (assert (nil? buffer))
   (assert (not (nil? subscription)))
   (set! buffer (when-not (== "" s) s))
   nil)
  #_(^#/(Future void) ^:async close [this]
   (when-some [sub subscription]
     (await (.cancel sub))
     (set! subscription nil)
     nil)))

(defn ^ReaderInput make-reader-input [^#/(async/StreamController String) controller]
  (doto (ReaderInput. (.-stream controller) nil nil nil) (.init_stream_subscription)))

;; read-* functions
(declare ^:async ^:dart read)

(defn ^int cu0 [^String ch] (.codeUnitAt ch 0))

(defn ^#/(Future cljd.core/PersistentList) ^:async read-list [^ReaderInput rdr]
  (let [result #dart[]]
    (loop []
      (let [val (await (read rdr (cu0 ")")))]
        (if (== val rdr)
          (-list-lit result)
          (do (.add result val)
              (recur)))))))

(defn ^#/(Future cljd.core/PersistentHashMap) ^:async read-hash-map [^ReaderInput rdr]
  (let [result #dart[]]
    (loop []
      (let [val (await (read rdr (cu0 "}")))]
        (if (== val rdr)
          (if (zero? (bit-and 1 (.-length result)))
            (-map-lit result)
            (throw (FormatException. "Map literal must contain an even number of forms")))
          (do (.add result val)
              (recur)))))))

(defn ^#/(Future cljd.core/PersistentVector) ^:async read-vector [^ReaderInput rdr]
  (let [result #dart[]]
    (loop []
      (let [val (await (read rdr (cu0 "]")))]
        (if (== val rdr)
          (if (< 32 (.-length result))
            (vec result)
            (-vec-owning result))
          (do (.add result val)
              (recur)))))))

(def ^RegExp COMMENT-CONTENT-REGEXP #"[^\r\n]*")

(defn ^:async read-comment [^ReaderInput rdr]
  (loop []
    (if-some [s (await (.read rdr))]
      (let [index (or (some-> (.matchAsPrefix COMMENT-CONTENT-REGEXP s) .end) 0)]
        (if (< index (.-length s))
          (doto rdr (.unread (.substring s index)))
          (recur)))
      rdr)))

(defn ^:async read-meta [^ReaderInput rdr]
  (let [meta (await (read rdr -1))
        meta (if (or (symbol? meta) (string? meta))
               {:tag meta}
               (if (keyword? meta)
                 {meta true}
                 (if (map? meta)
                   meta
                   (throw (FormatException. "Metadata must be Symbol,Keyword,String or Map")))))
        obj (await (read rdr -1))]
    (if (satisfies? cljd.core/IWithMeta obj)
      (with-meta obj meta)
      ;;TODO handle IReference with reset-meta
      (throw (FormatException. "Metadata can only be applied to IMetas")))))

(def ^RegExp STRING-CONTENT-REGEXP (RegExp. "(?:[^\"\\\\]|\\\\.)*"))
(def ^RegExp STRING-ESC-REGEXP (RegExp. "\\(?:u([0-9a-fA-F]{0,4})|([0-7]{1,3})|(.))"))

(defn ^:async read-string-content [^ReaderInput rdr]
  (let [f ^:async (fn ^String [^ReaderInput rdr]
                    (let [sb (StringBuffer.)]
                      (loop []
                        (if-some [^String string (await (.read rdr))]
                          (let [^int i (or (some-> (.matchAsPrefix STRING-CONTENT-REGEXP string) (.-end)) 0)]
                            (.write sb (.substring string 0 i))
                            (if (< i (.-length string))
                              (do (.unread rdr (.substring string (inc i)))
                                  (.toString sb))
                              (recur)))
                          (throw (FormatException. "Unexpected EOF while reading a string."))))))]
    #_(.replaceAllMapped (await (f rdr))
      STRING-ESC-REGEXP
      (fn [^Match m]
        (if-some [^String m1 (.group m 1)]
          (if (< (.-length m1) 4)
            (throw (FormatException. (str "Unsupported escape for character: \\u" m1 " \\u MUST be followed by 4 hexadecimal digits")))
            (String/fromCharCode (int/parse m1 .& :radix 16)))
          (if-some [^String m2 (.group m 2)]
            (String/fromCharCode (int/parse m2 .& :radix 8))
            (let [m3 (.group m 3)]
              (case m3
                "\"" (str m3)
                "\\" (str m3)
                "b" "\b"
                "n" "\n"
                "r" "\r"
                "t" "\t"
                "f" "\f"
                (throw (FormatException. (str "Unsupported escape character: \\" m3)))))))))
    (await (f rdr))))

(def dispatch-macros
  {"_" ^:async (fn [^ReaderInput rdr] (await (read rdr -1)) rdr)})

(def macros
  {"("  ^:async (fn [^ReaderInput rdr] (await (read-list rdr)))
   ")"  ^:async (fn [_] (throw (FormatException. "EOF while reading, starting at line")))
   "{"  ^:async (fn [^ReaderInput rdr] (await (read-hash-map rdr)))
   "}"  ^:async (fn [_] (throw (FormatException. "EOF while reading, starting at line")))
   "["  ^:async (fn [^ReaderInput rdr] (await (read-vector rdr)))
   "]"  ^:async (fn [_] (throw (FormatException. "EOF while reading, starting at line")))
   "'"  ^:async (fn [^ReaderInput rdr] (list (symbol nil "quote") (await (read rdr -1))))
   "@"  ^:async (fn [^ReaderInput rdr] (list 'cljd.core/deref (await (read rdr -1))))
   ";"  ^:async (fn [^ReaderInput rdr] (await (read-comment rdr)))
   "^"  ^:async (fn [^ReaderInput rdr] (await (read-meta rdr)))
   "\"" ^:async (fn [^ReaderInput rdr] (await (read-string-content rdr)))
   "#"  ^:async (fn [^ReaderInput rdr]
                  (if-some [string (await (.read rdr))]
                    (if-some [macroreader (dispatch-macros (aget string 0))]
                     (do (.unread rdr (.substring string 1))
                         (await (macroreader rdr)))
                     (throw (FormatException. (str "Unepxected dispatch sequence: #" (aget string 0)))))
                   (throw (FormatException. "EOF while reading dispatch sequence.")))) })

(def ^RegExp SPACE-REGEXP #"[\s,]*")

(defn ^bool terminating? [^int code-unit]
  (let [ch (String/fromCharCode code-unit)]
    (cond
      (< -1 (.indexOf "'#" ch)) false
      (macros ch) true
      (< 0 (or (some-> (.matchAsPrefix SPACE-REGEXP ch) .end) 0)) true
      :else false)))

(defn ^#/(Future String) ^:async read-token [^ReaderInput rdr]
  (let [sb (StringBuffer.)]
    (loop [^int index 0
           ^String string ""]
      (if (== index (.-length string))
        (do (.write sb string)
            (when-some [s (await (.read rdr))]
              (recur 0 s)))
        (let [cu (.codeUnitAt string index)]
          (if (terminating? cu)
            (do (.write sb (.substring string 0 index))
                (.unread rdr (.substring string index)))
            (recur (inc index) string)))))
    (.toString sb)))


(def ^RegExp INT-REGEXP (RegExp. "([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[0-9]+)(N)?$"))
(def ^RegExp DOUBLE-REGEXP (RegExp. "([-+]?[0-9]+([.][0-9]*)?([eE][-+]?[0-9]+)?)(M)?$"))
(def ^RegExp SYMBOL-REGEXP (RegExp. "(?:([:]{2})|([:]))?(?:([^0-9/:].*)/)?(/|[^0-9/:][^/]*)$"))

(defn interpret-token [^String token]
  (case token
    "nil" nil
    "true" true
    "false" false
    (if-some [m (.matchAsPrefix INT-REGEXP token)]
      (let [parse (if ^some (.group m 8)
                    (if (== "-" (.group m 1))
                      (fn ^BigInt [^String s ^int radix] (- (BigInt/parse s .& :radix radix)))
                      (fn ^BigInt [^String s ^int radix] (BigInt/parse s .& :radix radix)))
                    (if (== "-" (.group m 1))
                      (fn ^int [^String s ^int radix] (- (int/parse s .& :radix radix)))
                      (fn ^int [^String s ^int radix] (int/parse s .& :radix radix))))]
        (cond
          (not (nil? (.group m 2))) 0
          (not (nil? (.group m 3))) (parse (.group m 3) 10)
          (not (nil? (.group m 4))) (parse (.group m 4) 16)
          (not (nil? (.group m 5))) (parse (.group m 5) 8)
          (not (nil? (.group m 7))) (parse (.group m 7) (int/parse ^String (.group m 6)))
          :else (throw (FormatException. "Invalid number."))))
      (if-some [m (.matchAsPrefix DOUBLE-REGEXP token)]
        (if (.group m 4)
          (throw (FormatException. "BigDecimal not supported yet."))
          (double/parse token))
        (if (or (< 0 (.lastIndexOf token "::")) (.endsWith token ":"))
          (throw (FormatException. (str "Invalid token: " token)))
          (if-some [^Match m (.matchAsPrefix SYMBOL-REGEXP token)]
            (cond
              (not (nil? (.group m 1))) (throw (Exception. "TO IMPLEMENT"))
              (not (nil? (.group m 3))) (let [ns (.group m 3)]
                                          (when (.endsWith ns ":")
                                            (throw (FormatException. (str "Invalid token: " token))))
                                          (keyword ns (.group m 4)))
              :else (keyword nil (.group m 4)))
            (throw (FormatException. (str "Invalid token: " token)))))))))

(defn ^#/(Future dynamic) ^:async read
  [^ReaderInput rdr ^int delim]
  (loop []
    (if-some [string (await (.read rdr))]
      (let [index (or (some-> (.matchAsPrefix SPACE-REGEXP string) .end) 0)]
        (if (== index (.-length string))
          (recur)
          (let [ch (.codeUnitAt string index)]
            (if (== delim ch)
              (doto rdr (.unread (.substring string (inc index))))
              (if-some [macro-reader (macros (aget string index))]
                (do (.unread rdr (.substring string (inc index)))
                    (let [val (await (macro-reader rdr))]
                      (if (== val rdr)
                        (recur)
                        val)))
                (do (.unread rdr (.substring string index))
                    (-> (await (read-token rdr)) interpret-token)))))))
      (if (< delim 0)
        rdr
        (throw (FormatException. (str "Unexpected EOF, expected " (String/fromCharCode delim))))))))

(defn ^#/(Future dynamic) ^:async read-string [^String s]
  (let [controller (new #/(async/StreamController String))
        rdr (make-reader-input controller)]
    (.add controller s)
    (let [res (read rdr -1)]
      (.close controller)
      (await res))))

(defn ^:async main []
  (as-> (await (read-string "(12 12N -12 0x12 0X12 0x1ff)")) r (prn r (meta r) (.-runtimeType r)))
  (as-> (await (read-string "(12.3 0.2 -1.2 0.0)")) r (prn r (meta r) (.-runtimeType r)))
  (as-> (await (read-string "(:aaa :aa/bb :aa:adsf:sdf :dd///)")) r (prn r (meta r) (.-runtimeType r)))
  (as-> (await (read-string "(:aaa #_(1 2 3) 1 2 3 )")) r (prn r (meta r) (.-runtimeType r)))
  (as-> (await (read-string "nil")) r (prn r (.-runtimeType r)))
  (as-> (await (read-string "^{true true} [\"nil\" nil]")) r (prn r (meta r) (.-runtimeType r)))
  (as-> (await (read-string "^{true true} [\"n\\\"il\" nil]")) r (prn r (meta r) (.-runtimeType r)))
  (as-> (await (read-string "^{true true} [nil nil]")) r (prn r (meta r) (.-runtimeType r)))
  (as-> (await (read-string "'(true false false)")) r (prn r (.-runtimeType r)))
  (as-> (await (read-string "@true")) r (prn r (.-runtimeType r)))
  (as-> (await (read-string ";;coucou text \n (true true)")) r (prn r (.-runtimeType r)))
  (as-> (await (read-string "(true true nil)")) r (prn r (.-runtimeType r)))
  (as-> (await (read-string "[true true nil]")) r (prn r (.-runtimeType r)))
  (as-> (await (read-string "{true true nil nil}")) r (prn r (.-runtimeType r)))
  (as-> (await (read-string "{true true nil [true true]}")) r (prn r (.-runtimeType r))))
