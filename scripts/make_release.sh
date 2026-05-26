
set -e
set -x

SHA="$1"
RELEASE="$2"

clojure -Sdeps "{:mvn/repos {\"dewey-s3-repo\" {:url \"s3://com-phronemophobic-dewey/snapshots\"}} :deps {com.phronemophobic/dewey {:git/sha \"${SHA}\", :git/url \"https://github.com/phronmophobic/dewey\"}}}" -M --report stderr -e "(require (quote com.phronemophobic.dewey.etl))(ns com.phronemophobic.dewey.etl)(make-release \"${RELEASE}\" \"${SHA}\" )"


