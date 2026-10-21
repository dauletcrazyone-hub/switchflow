; SwitchFlow NSIS hooks.
; Phones connect to the PC over the local network, so the installer allows the
; app through Windows Firewall on PRIVATE networks only. Public networks stay blocked.

!macro NSIS_HOOK_POSTINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="SwitchFlow"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="SwitchFlow" dir=in action=allow program="$INSTDIR\switchflow.exe" enable=yes profile=private protocol=TCP'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="SwitchFlow"'
!macroend
