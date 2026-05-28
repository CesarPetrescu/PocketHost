.PHONY: test go-test verify-daemons ci build-go-android-arm64 package

test: go-test

go-test:
	cd go && go test ./...

verify-daemons:
	./scripts/verify-daemons-local.sh

ci:
	./scripts/ci-local.sh

build-go-android-arm64:
	./scripts/build-go-android.sh arm64-v8a

package:
	./scripts/package-android.sh
