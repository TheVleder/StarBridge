; Inno Setup script for an installer of the PC program (optional: the portable zip needs none).
;   1. gradlew :desktop:packageWindows       (builds desktop\build\jpackage\StarBridge)
;   2. iscc packaging\windows\StarBridge.iss  (Inno Setup 6, https://jrsoftware.org/isinfo.php)
; Output: build\release\StarBridge-PC-<version>-setup.exe. Installs per user (no admin rights).

#define AppVersion "0.6.0"

[Setup]
AppId={{F77C8D5F-F338-4988-AF58-6FDEAD521CA6}
AppName=StarBridge
AppVersion={#AppVersion}
AppPublisher=StarBridge
AppPublisherURL=https://github.com/TheVleder/starbridge
DefaultDirName={localappdata}\Programs\StarBridge
DefaultGroupName=StarBridge
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\build\release
OutputBaseFilename=StarBridge-PC-{#AppVersion}-setup
SetupIconFile=StarBridge.ico
UninstallDisplayIcon={app}\StarBridge.exe
LicenseFile=..\..\LICENSE.md
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\..\desktop\build\jpackage\StarBridge\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\NOTICE.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\StarBridge"; Filename: "{app}\StarBridge.exe"
Name: "{autodesktop}\StarBridge"; Filename: "{app}\StarBridge.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\StarBridge.exe"; Description: "{cm:LaunchProgram,StarBridge}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "taskkill.exe"; Parameters: "/F /IM StarBridge.exe"; Flags: runhidden; RunOnceId: "StopStarBridge"
