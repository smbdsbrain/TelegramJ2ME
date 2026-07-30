# Debug-build ProGuard options. Included by tools/build.ps1 only when -Release
# is NOT passed.
#
# Preverification still happens either way - that is -microedition in
# proguard-common.pro and it is not optional. What these two lines buy is a
# readable JAR: class and method names survive, so a stack trace pasted into an
# issue by someone running this on a real handset names actual code.
#
# The project has never been executed on physical hardware, so that is worth
# more than the kilobytes optimisation would save. Release builds drop this
# file and take the smaller JAR instead - see docs/releasing.md.

-dontoptimize
-dontobfuscate
