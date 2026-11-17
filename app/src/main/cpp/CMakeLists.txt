cmake_minimum_required(VERSION 3.22)
project(bench_jni)

# CRITIQUE : forcer Release (-O3). AGP configure le code natif des
# APK « debug » en CMAKE_BUILD_TYPE=Debug (-O0) ; ggml y perd
# l'essentiel de sa vitesse et les drapeaux -march n'y changent rien
# (constaté le 2026-11-16 : 0,65 jeton/s en lecture du prompt).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Pages mémoire de 16 Ko (Pixel 8 Pro sous GrapheneOS récent) :
# sans cet alignement, Android refuse de charger les bibliothèques
# natives (dlopen échoue, l'écran du banc plantait à l'ouverture —
# constaté le 2026-11-16, alignement lu à 0x1000 dans l'APK).
add_link_options("-Wl,-z,max-page-size=16384")

# Depuis le 2026-11-16 au soir, le banc est un banc d'ÉCOUTE :
# whisper.cpp en sous-module, épinglé par le dépôt (voir
# .gitmodules). L'ancien moteur d'extraction (llama.cpp) a été
# retiré du build — dossier clos : le modèle local est abandonné
# comme interprète (fiabilité), la transcription reste locale.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Noyaux ARM optimisés : en compilation croisée (Android), ggml
# désactive GGML_NATIVE et retombe sur un « armv8-a » générique —
# les chemins rapides (produit scalaire, i8mm) ne sont jamais
# compilés. Le Tensor G3 (X3 + A715 + A510) supporte dotprod et
# i8mm : on les demande explicitement.
set(GGML_CPU_ARM_ARCH "armv9.2-a+dotprod+i8mm" CACHE STRING "" FORCE)

# Micro-noyaux KleidiAI (ARM) : chemins ggml optimisés sur les
# opérations matricielles de whisper.
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(bench_jni SHARED whisper_jni.cpp)
target_link_libraries(bench_jni PRIVATE whisper android log)
