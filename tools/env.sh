# Source me before using the tools in this folder. Machine-specific settings go in tools/local.env
# (git-ignored; copy tools/local.env.example): the TV's adb address, release signing, toolchain paths.
_nimbus_tools="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$_nimbus_tools/local.env" ]; then
  # shellcheck source=/dev/null
  source "$_nimbus_tools/local.env"
fi
unset _nimbus_tools
