; Установщик программы для ПК (Inno Setup 6).
; Ставит для текущего пользователя, без прав администратора: в %LOCALAPPDATA%\Programs\PodsCompanion,
; ярлык в меню «Пуск», по желанию — автозапуск. Повторный запуск поверх обновляет программу.
; Сборка: iscc /DAppVersion=0.2.0 /DSourceExe=..\out\PodsCompanion.exe PodsCompanion.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceExe
  #define SourceExe "..\out\PodsCompanion.exe"
#endif

[Setup]
AppId={{6E2C1B5A-7F3D-4C8E-9A41-2B7D5E0F8C13}
AppName=Pods Companion
AppVersion={#AppVersion}
AppPublisher=Pods Companion
DefaultDirName={localappdata}\Programs\PodsCompanion
DefaultGroupName=Pods Companion
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
OutputDir=..\out
OutputBaseFilename=PodsCompanion-Setup
SetupIconFile=..\src\PodsCompanion.App\Assets\app.ico
UninstallDisplayIcon={app}\PodsCompanion.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Тот же мьютекс, что держит запущенная программа: установщик закроет её перед заменой файла.
AppMutex=PodsCompanion.SingleInstance
CloseApplications=force
RestartApplications=no

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"

[CustomMessages]
ru.AutoStart=Запускать вместе с Windows
en.AutoStart=Start with Windows
fr.AutoStart=Lancer avec Windows
ru.LaunchNow=Запустить Pods Companion
en.LaunchNow=Launch Pods Companion
fr.LaunchNow=Lancer Pods Companion

[Tasks]
Name: "autostart"; Description: "{cm:AutoStart}"

[Files]
Source: "{#SourceExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\Pods Companion"; Filename: "{app}\PodsCompanion.exe"

[Registry]
; Та же запись, что ставит галочка «Запускать вместе с Windows» в самой программе.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "PodsCompanion"; \
  ValueData: """{app}\PodsCompanion.exe"" --background"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
; Работает и при тихой установке из кнопки «Обновить»: программа запускается снова сама.
Filename: "{app}\PodsCompanion.exe"; Description: "{cm:LaunchNow}"; Flags: nowait postinstall

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM PodsCompanion.exe /F"; Flags: runhidden; RunOnceId: "StopApp"
