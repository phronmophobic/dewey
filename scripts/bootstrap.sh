
set -e
set -x

apt -y update
DEBIAN_FRONTEND=noninteractive apt -y install emacs-lucid openjdk-18-jre-headless rlwrap awscli unzip leiningen git build-essential

wget https://download.clojure.org/install/linux-install.sh
chmod 755 linux-install.sh
./linux-install.sh
clojure -P

wget https://raw.githubusercontent.com/phronmophobic/dev-env/master/.simpleemacs
mv .simpleemacs ~/.emacs




