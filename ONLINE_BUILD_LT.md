# TREVORAS – APK sukūrimas internetu

Ši versija paruošta GitHub Actions. Kompiuteryje Android Studio nereikia.

1. GitHub sukurk naują tuščią repository, pvz. `TREVORAS`.
2. Į repository šaknį įkelk VISĄ šio ZIP aplanko `TREVORAS_Full` turinį.
   Svarbu: GitHub pagrindiniame lange turi matytis `app`, `.github`,
   `build.gradle.kts`, `settings.gradle.kts`.
3. Commit failus.
4. Atidaryk `Actions`.
5. Pasirink `Build TREVORAS APK`.
6. Spausk `Run workflow`.
7. Palauk, kol build taps žalias.
8. Atidaryk tą workflow run.
9. Apačioje, `Artifacts`, atsisiųsk `TREVORAS-APK`.
10. Išarchyvuok – viduje bus `TREVORAS.apk`.

Workflow naudoja Java 17, Gradle 8.10.2 ir Android Gradle Plugin 8.7.3.
Gradle Wrapper failų nereikia: GitHub runneris parsisiunčia Gradle per
`gradle/actions/setup-gradle`.

Jei build nepavyktų, atidaryk raudoną `Build debug APK` žingsnį ir nukopijuok
visą klaidos tekstą į ChatGPT – pagal jį pataisysime projektą.
