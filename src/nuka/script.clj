(ns nuka.script
  (:require [clojure.walk :as walk]))

(defrecord SingleQuotedArg [val])
(defrecord DoubleQuotedArg [val])
(defrecord NumericArg [val])
(defrecord Flag [val])
(defrecord NamedArg [name val])
(defrecord Command [cmd args])
(defrecord EmbeddedCommand [cmd])
(defrecord Script [commands])
(defrecord Raw [val])
(defrecord Pipe [commands])
(defrecord ChainAnd [commands])
(defrecord ChainOr [commands])
(defrecord Reference [val])
(defrecord Loop [binding coll commands])
(defrecord InlineBlock [commands])

(defn render-flag-name [f]
  (when f
    (if (string? f) f
        (let [s (name f)]
          (if (or (.startsWith s "--")
                  (.startsWith s "-"))
            s
            (if (= 1 (count s))
              (str "-" s)
              (str "--" s)))))))

(defmulti parse-arg-set
  (fn [x] (cond (record? x) (class x)
                (map? x) :map
                (vector? x) :vector
                (string? x) :string
                (keyword? x) :keyword
                (number? x) :number
                (symbol? x) :symbol
                :else :default)))

(defmethod parse-arg-set :default [x] x)
(defmethod parse-arg-set Command [x] (->EmbeddedCommand x))
(defmethod parse-arg-set :symbol [x] (->Reference x))
(defmethod parse-arg-set :string [s] (SingleQuotedArg. s))
(defmethod parse-arg-set :number [x] (NumericArg. x))
(defmethod parse-arg-set :keyword [k] (Flag. k))
(defmethod parse-arg-set :vector [v] (map parse-arg-set v))
(defmethod parse-arg-set :map [m]
  (mapcat (fn [[k v]] (cond (true? v)   [(Flag. k)] ;;keys with true values are just flags
                            (false? v)  [] ;;keys with false values are skipped
                            (keyword v) [(NamedArg. k (parse-arg-set (name v)))]
                            :else       [(NamedArg. k (parse-arg-set v))])) m))

(defn raw [x] (->Raw x))

(defn command [cmd & arg-sets]
  (->Command cmd (mapcat (fn [x]
                           (let [parsed (parse-arg-set x)]
                             (if (seq? parsed) parsed [parsed]))) arg-sets)))

(defn pipe [& commands]
  (->Pipe commands))

(defn chain-and [& commands]
  (->ChainAnd commands))

(defn chain-or [& commands]
  (->ChainOr commands))

(defn block [& commands]
  (->InlineBlock commands))

(defn q [s]
  (->SingleQuotedArg s))

(defn qq [s]
  (->DoubleQuotedArg s))

(defn raw [s]
  (->Raw s))

(defn script* [& commands]
  (->Script commands))

(defn- unquote-form?
  "Tests whether the form is (clj ...) or (unquote ...) or ~expr."
  [form]
 (or (and (seq? form)
          (symbol? (first form))
          (= (symbol (name (first form))) 'clj))
     (and (seq? form) (= (first form) `unquote))))

(def constructors
  #{'pipe 'chain-and 'chain-or 'q 'qq 'block 'raw})

(defn- fully-qualify-constructor [form]
  (cons
   (symbol (str "nuka.script/" (first form)))
   (rest form)))

(defn- constructor-form? [form]
  (and (list? form) (some? (constructors (first form)))))

(defn- loop-form? [form]
  (and (list? form) (= 'doseq (first form))))

(defn- command-form?
  [form]
  (or (and (not (unquote-form? form))
        (not (constructor-form? form))
        (list? form)
        (or (symbol? (first form))
            (string? (first form))))
      (and (list? form) (unquote-form? (first form)))))

(deftype WalkGuard [value])

(defn- process-script-pass1 [commands]
  (walk/prewalk
   (fn [form]
     (cond
       (unquote-form? form) (WalkGuard. (second form))
       (constructor-form? form)
       (fully-qualify-constructor form)

       (loop-form? form)
       (let [[_ [b coll] & commands] form]
         `(->Loop '~b (->EmbeddedCommand ~coll) ~(vec commands)))

       (command-form? form)
       (concat (list 'nuka.script/command
                     (if (unquote-form? (first form))
                       (-> form first second)
                       (str (first form))))
               (map (fn [x] (cond (symbol? x) `'~x
                                  (unquote-form? x) x
                                  :else x)) (rest form)))
       
       :else
       form)) (vec commands)))

(defn- process-script-pass2 [commands]
  (walk/postwalk
   (fn [form]
     (if (instance? WalkGuard form) (.value form) form)) commands))

(defn- process-script [commands]
  (-> commands process-script-pass1 process-script-pass2))

(defmacro script [& commands]
  `(script*
    ~@(process-script (vec commands))))

(defn single-quote [s]
  (str "'" s "'"))

(defn double-quote [s]
  (str "\"" s "\""))
