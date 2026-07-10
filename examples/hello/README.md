
```shell
# in this dir
deder bsp install

cd scalaide
scala setup-ide .

cd sbt
sbt bspConfig

cd ..
# int this dir
code --disable-extension scalameta.metals --extensionDevelopmentPath="../../../basamake-vscode" .
 