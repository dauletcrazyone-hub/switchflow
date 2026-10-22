; SwitchFlow NSIS hooks.
; Phones connect to the PC over the local network, so the installer allows the
; app through Windows Firewall on PRIVATE networks only. Public networks stay blocked.
;
; Cable transfer without USB debugging (Android accessory mode) needs the UsbDk USB driver
; (Red Hat / Daynix, Apache-2.0): Windows has no driver for accessory mode. It is installed
; with the app and left in place on uninstall, since other programs may use it too.

!macro NSIS_HOOK_POSTINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="SwitchFlow"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="SwitchFlow" dir=in action=allow program="$INSTDIR\switchflow.exe" enable=yes profile=private protocol=TCP'
  ; Already installed (by us or another program): msiexec leaves it as it is.
  nsExec::Exec 'msiexec /i "$INSTDIR\drivers\UsbDk_x64.msi" /qn /norestart'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="SwitchFlow"'
!macroend
