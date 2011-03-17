(ns necessary-evil.value
  "This module parses and creates xml for xml-rpc value nodes. This is
   shared by the various forms required for method calls, responses and
   response faults.

   The library has a pair of functions, parse-value and value-elem,
   that are used for reading and writing values.

   The value types handled by this library can be extended by creating
   new method implementations for the parse-value multimethod where
   the name of the element is a clojure keyword.

   You should also extend the ValueTypeElem protocol to support the
   return type of your new method implementation to generate the xml
   that your parse-value implementation consumes."
  (:use [clojure.contrib.zip-filter.xml :only [xml-> xml1-> text]]
        [necessary-evil.xml-utils])
  (:require [clojure.zip :as zip]
            [clojure.xml :as xml]
            [clojure.contrib.zip-filter :as zf]
            [clojure.string :as su]
            [clj-time.core :as time]
            [clj-time.format :as time-format])
  (:import org.apache.commons.codec.binary.Base64))

;; dave winer has kindly defined his own variation on ISO 8601 date
;; time formatting
(def winer-time (time-format/formatter "yyyyMMdd'T'HH:mm:ss"))

;; The following implements deserialization of values with a multi-method

(declare parse-value)

(defn strip-leading-plus [s] (su/replace (.trim  s) #"^\+" ""))

(defn parse-int [v] (Integer. (strip-leading-plus (text v))))

(defn parse-bool [v]
  (condp = (.trim (text v))
      "0" false
      "1" true
      "throw exception here"))

(defn parse-struct-member [m] (let [name (xml1-> m :name first-child)
                                    val  (xml1-> m :value first-child)]
                                (if (or name val)
                                  [(keyword (-> name text .trim)),
                                   (parse-value val)]
                                  [nil, nil])))

(defn parse-struct [v] (into {} (map parse-struct-member
                                     (xml-> v :struct :member))))

(defn parse-array [v] (vec (map parse-value
                                (xml-> v :array :data :value first-child))))

(defmulti parse-value #(:tag (zip/node %)))

(defmethod parse-value :i4               [v] (parse-int v))
(defmethod parse-value :int              [v] (parse-int v))

(defmethod parse-value :boolean          [v] (parse-bool v))
(defmethod parse-value :string           [v] (text v))
(defmethod parse-value :double           [v] (Double. (strip-leading-plus
                                                       (text v))))
(defmethod parse-value :dateTime.iso8601 [v] (time-format/parse winer-time
                                                                (text v)))
(defmethod parse-value :base64           [v] (-> (text v) Base64/decodeBase64))
(defmethod parse-value :struct           [v] (parse-struct v))
(defmethod parse-value :array            [v] (parse-array v))
(defmethod parse-value :default          [v] (text v))


;; The following implements serialization of values with a protocol.

(defprotocol ValueTypeElem
  "ValueTypeElem defines a single function 'value-type-elem' that is used to emit
   various types as values for the xml-rpc format"
  (value-type-elem [x]))

(defn value-elem
  "value-elem creates the <value> elements and the appropriate child element for it's
   arguments. This calls out to value-type-elem."
  [content] (elem :value [(value-type-elem content)]))

(defn- struct-member-elem
  "creates an xml element for a key-value pair."
  [[key value]] (elem :member [(elem :name [(name key)])
                               (value-elem value)]))

(extend-protocol ValueTypeElem
  String
  (value-type-elem [this] (elem :string [this]))

  Integer
  (value-type-elem [this] (elem :int [(str this)]))

  Boolean
  (value-type-elem [this] (elem :boolean [(if this "1" "0")]))

  Double
  (value-type-elem [this] (elem :double [(str this)]))

  org.joda.time.DateTime
  (value-type-elem [this] (elem :dateTime.iso8601 [(time-format/unparse
                                                    winer-time
                                                    this)]))

  clojure.lang.IPersistentVector ; a vector becomes an xml-rpc array
  (value-type-elem [this] (elem :array [(elem :data (vec (map value-elem this)))]))

  clojure.lang.IPersistentMap ; a map becomes a xml-rpc struct
  (value-type-elem [this] (elem :struct (vec (map struct-member-elem this))))

  Object
  (value-type-elem [this] (elem :string [(str this)])))

;; extends ValueTypeElem for Byte Arrays. This slightly is awkward,
;; perhaps there is a better approach
(extend (Class/forName "[B")
   ValueTypeElem
   {:value-type-elem (fn [this] (elem :base64 [(String. (Base64/encodeBase64 this))]))})



