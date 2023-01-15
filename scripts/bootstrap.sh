
set -e
set -x

apt -y update
DEBIAN_FRONTEND=noninteractive apt -y install emacs-lucid openjdk-18-jre-headless rlwrap awscli unzip leiningen git build-essential
NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

echo '# Set PATH, MANPATH, etc., for Homebrew.' >> /home/ubuntu/.profile
echo 'eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"' >> /home/ubuntu/.profile
eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"


brew install gcc
brew install clojure/tools/clojure
