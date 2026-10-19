APP_NAME    := Mirage
HELPER_NAME := MirageDisplayHelper
BUNDLE      := build/$(APP_NAME).app
DMG         := build/$(APP_NAME).dmg
CONFIG      := release

# Signing. Falls back to ad-hoc if no Apple Development identity is present.
# Ad-hoc works, but macOS re-asks for Screen Recording after every rebuild because TCC
# keys the grant on the code signature.
SIGN_ID ?= $(shell security find-identity -v -p codesigning 2>/dev/null | \
             grep -m1 "Apple Development" | sed -E 's/.*"(.*)"/\1/')
ifeq ($(strip $(SIGN_ID)),)
SIGN_ID := -
endif

# Android app (android/). ANDROID_SERIAL picks the device when more than one is attached.
ANDROID_DIR := android
ANDROID_APK := $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk
GRADLE      := cd $(ANDROID_DIR) && ./gradlew
# Gradle's installDebug (via adb) reads ANDROID_SERIAL from the environment, so pass it through
# whether it was set in the shell or as `make android-install ANDROID_SERIAL=R52W...`.
ifdef ANDROID_SERIAL
export ANDROID_SERIAL
endif

.PHONY: all build universal bundle universal-bundle dmg icon run test clean install-remote help \
        android android-install android-test

all: bundle

help:
	@echo "make            build a native .app into build/"
	@echo "make universal-bundle   build arm64 + x86_64 (needed for the 2017 Intel Mac)"
	@echo "make dmg        build a universal .dmg you can copy to the other Mac"
	@echo "make run        build and launch"
	@echo "make test       run the protocol, codec and virtual-display checks (+ Android unit tests)"
	@echo "make install-remote HOST=user@mac   copy straight to the other Mac over ssh"
	@echo "make icon       regenerate the app icon from Tools/make-icon.swift"
	@echo "make android    build the Android app (debug APK) into $(ANDROID_APK)"
	@echo "make android-install   install it on the connected tablet (ANDROID_SERIAL=... to pick one)"
	@echo "make android-test      run the Android unit tests"
	@echo "make clean"

build:
	swift build -c $(CONFIG)

universal:
	swift build -c $(CONFIG) --arch arm64 --arch x86_64

icon:
	@swiftc -O Tools/make-icon.swift -o $(TMPDIR)make-icon && $(TMPDIR)make-icon Resources

Resources/Mirage.icns:
	@$(MAKE) --no-print-directory icon

bundle: Resources/Mirage.icns build
	@$(MAKE) --no-print-directory _bundle BIN=$$(swift build -c $(CONFIG) --show-bin-path)

universal-bundle: Resources/Mirage.icns universal
	@$(MAKE) --no-print-directory _bundle BIN=$$(swift build -c $(CONFIG) --arch arm64 --arch x86_64 --show-bin-path)

_bundle:
	@rm -rf $(BUNDLE)
	@mkdir -p $(BUNDLE)/Contents/MacOS $(BUNDLE)/Contents/Resources
	@cp "$(BIN)/$(APP_NAME)" $(BUNDLE)/Contents/MacOS/$(APP_NAME)
	@cp "$(BIN)/$(HELPER_NAME)" $(BUNDLE)/Contents/MacOS/$(HELPER_NAME)
	@cp Resources/Info.plist $(BUNDLE)/Contents/Info.plist
	@cp Resources/Mirage.icns $(BUNDLE)/Contents/Resources/Mirage.icns
	@printf 'APPL????' > $(BUNDLE)/Contents/PkgInfo
	@# The helper is signed first: codesign seals nested code, so it must be settled
	@# before the outer bundle signature is computed.
	@codesign --force --options runtime --sign "$(SIGN_ID)" \
	          $(BUNDLE)/Contents/MacOS/$(HELPER_NAME) 2>&1 | sed 's/^/  codesign helper: /'
	@codesign --force --options runtime \
	          --entitlements Resources/Mirage.entitlements \
	          --sign "$(SIGN_ID)" $(BUNDLE) 2>&1 | sed 's/^/  codesign app: /'
	@codesign --verify --deep --strict $(BUNDLE) && echo "  signature verified"
	@echo "built $(BUNDLE)  (identity: $(SIGN_ID))"
	@lipo -archs $(BUNDLE)/Contents/MacOS/$(APP_NAME) | sed 's/^/  architectures: /'

dmg: universal-bundle
	@rm -rf build/dmg $(DMG)
	@mkdir -p build/dmg
	@cp -R $(BUNDLE) build/dmg/
	@ln -s /Applications build/dmg/Applications
	@cp Resources/INSTALL.txt build/dmg/"Read Me First.txt" 2>/dev/null || true
	@hdiutil create -volname "$(APP_NAME)" -srcfolder build/dmg -ov -format UDZO $(DMG) >/dev/null
	@rm -rf build/dmg
	@echo "built $(DMG)  ($$(du -h $(DMG) | cut -f1))"
	@echo "Copy it to the other Mac, drag Mirage to Applications, then run:"
	@echo "  xattr -dr com.apple.quarantine /Applications/$(APP_NAME).app"

run: bundle
	@pkill -x $(APP_NAME) 2>/dev/null || true
	open $(BUNDLE)

test:
	@bash Tests/run-tests.sh
	@if [ -x $(ANDROID_DIR)/gradlew ]; then \
	    printf "\n\033[1m── android unit tests ──\033[0m\n"; \
	    $(MAKE) --no-print-directory android-test; \
	fi

android:
	$(GRADLE) assembleDebug
	@echo "built $(ANDROID_APK)"

android-install:
	$(GRADLE) installDebug

android-test:
	$(GRADLE) testDebugUnitTest

install-remote: universal-bundle
	@test -n "$(HOST)" || (echo "usage: make install-remote HOST=user@othermac" && exit 1)
	rsync -az --delete $(BUNDLE) "$(HOST):/Applications/"
	ssh "$(HOST)" 'xattr -dr com.apple.quarantine /Applications/$(APP_NAME).app'
	@echo "installed on $(HOST)"

clean:
	swift package clean
	rm -rf build .build
