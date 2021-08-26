# Maintainer: Stefan Silviu Alexandru <stefan.silviu.alexandru@gmail.com>
pkgname=ckompiler
pkgver=SNAPSHOT6
pkgrel=1
pkgdesc='A C11 compiler written in Kotlin'
arch=('any')
url='https://github.com/slak44/ckompiler'
license=('MIT')
depends=('java-environment>=11' 'java-runtime>=11')
optdepends=('nasm: for assembling compiled files'
            'graphviz: CFG viewing support')
provides=('ckompiler')
source=("https://github.com/slak44/ckompiler/archive/$pkgver.zip")
sha256sums=('a71209ed85c7fc0e5894f7f8e884def7c2715161a8db7643066fe4ae9debe65b')

javaVer="$(archlinux-java get | cut -d '-' -f2)"
if [ "$javaVer" -lt "11" ]; then
  echo "archlinux-java reports the current version to be java $javaVer; this package requires at least 11 to be set"
fi

function runGradle() {
  ./gradlew -g "./dot-gradle" "$@"
}

build() {
  cd "$pkgname-$pkgver"
  runGradle build
}

check() {
  cd "$pkgname-$pkgver"
  runGradle check
}

package() {
  cd "$pkgname-$pkgver"
  DESTDIR="$pkgdir/" runGradle installDist
  # Install license
  install -d "$pkgdir/usr/share/licenses/$pkgname/"
  install -Dm644 LICENSE "$pkgdir/usr/share/licenses/$pkgname/"
  # Move the jars from /lib to inside /usr/share/java
  install -d "$pkgdir/usr/share/java/$pkgname/"
  mv "$pkgdir/lib" "$pkgdir/usr/share/java/$pkgname/"
  # Delete APP_HOME detection
  sed -i "25,43d" "$pkgdir/usr/bin/ckompiler"
  # Set the APP_HOME property to where we actually put the thing
  sed -i "25iAPP_HOME=\/usr\/share\/java\/$pkgname\/" "$pkgdir/usr/bin/ckompiler"
  # We're not on windows
  rm "$pkgdir/usr/bin/$pkgname.bat"
}
