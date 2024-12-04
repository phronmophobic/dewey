
set -e
set -x

apt -y update
DEBIAN_FRONTEND=noninteractive apt -y install emacs-lucid openjdk-21-jre-headless rlwrap unzip leiningen git build-essential

curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
./aws/install

curl -O https://download.clojure.org/install/linux-install.sh
chmod 755 linux-install.sh
./linux-install.sh
clojure -P

curl -O https://raw.githubusercontent.com/phronmophobic/dev-env/master/.simpleemacs
mv .simpleemacs ~/.emacs

