.PHONY: test go-test verify-daemons ci check audit build-go-android-arm64 package

test: go-test

go-test:
	cd go && go test ./...

verify-daemons:
	./scripts/verify-daemons-local.sh

ci:
	./scripts/ci-local.sh

# Repository consistency checks (the subset that does not need a clean tree).
check:
	./scripts/ci/check-repo.sh local

# Provenance and attribution of the committed native payload. Expected to fail
# today; see .github/workflows/audit.yml.
audit:
	./scripts/ci/audit-artifacts.sh all

build-go-android-arm64:
	./scripts/build-go-android.sh arm64-v8a

package:
	./scripts/package-android.sh
