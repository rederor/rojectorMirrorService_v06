# ProjectorMirrorService 0.6

Wersja usługowa. Aktywność tylko prosi o zgodę na USB i MediaProjection, a właściwa praca dzieje się w Foreground Service.

## Parametry

- USB Accessory: `Mirroring / gan mirroring / 1.0`
- VirtualDisplay: 800x480
- H.264 AVC, 30 fps, 1.2 Mbps
- heartbeat: odpowiada `00 00 00 00` na `00 00 00 00`
- bez audio

## Budowanie

Wrzuć zawartość projektu do root repozytorium GitHub.

`Actions → Build debug APK → Run workflow`

Artefakt: `ProjectorMirrorService-v06-debug-apk`

## Log

```bash
adb pull /sdcard/Android/data/pl.test1.projectormirrorservice/files/projector_mirror_service_log.txt
```

## Test

1. Zatrzymaj stare aplikacje testowe i oryginalne `Mirroring`.
2. Podłącz projektor.
3. Uruchom `Projector Mirror Service`.
4. Kliknij `Wykryj USB`.
5. Kliknij `Zgoda USB`, jeśli potrzebna.
6. Kliknij `START ekran`.
7. Zaakceptuj systemowe udostępnianie ekranu.
8. Jeśli UI zniknie, usługa powinna dalej zapisywać log do pliku.


## Zmiany w 0.6

Na podstawie logu projektor po ACK wysłał pakiet:

`18 00 00 00 ...` o długości 24 bajtów.

Wersja 0.6:
- czeka do 3,5 s na pierwszy niezerowy pakiet USB;
- jeśli go dostanie, odsyła ten pakiet w całości jako echo;
- dopiero potem uruchamia enkoder;
- wysyła SPS/PPS i klatki jako pakiety z 4-bajtową długością little-endian;
- długość oznacza całą długość pakietu: 4 bajty nagłówka + payload.

To jest hipoteza protokołu, nie pewne rozwiązanie.
