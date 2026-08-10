
deder exec -t assembly -m modules-main && cp .deder/out/modules-main/assembly/out.jar ../basamake-vscode/basamake.jar


VERSION=v0.2.0 && git tag -a $VERSION -m "$VERSION" && git push origin $VERSION




