
set -e
set -x

SHA="$1"
RELEASE="$2"

clojure -J"--add-opens=java.base/java.nio=ALL-UNNAMED" -J"--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" -Sdeps "{:mvn/repos {\"dewey-s3-repo\" {:url \"s3://com-phronemophobic-dewey/snapshots\"}} :deps {com.phronemophobic/dewey {:git/sha \"${SHA}\", :git/url \"https://github.com/phronmophobic/dewey\"}}}" -M --report stderr -e "(require (quote com.phronemophobic.dewey.etl))(ns com.phronemophobic.dewey.etl)(run \"${RELEASE}\" )"



