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

# llama.cpp en sous-module, épinglé par le dépôt (voir .gitmodules).
# Banc d'essai seulement : ni serveur, ni exemples, ni tests.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Noyaux ARM optimisés : en compilation croisée (Android), ggml
# désactive GGML_NATIVE et retombe sur un « armv8-a » générique —
# les chemins rapides (produit scalaire, i8mm) ne sont jamais
# compilés. Résultat mesuré le 2026-11-16 (runs 1 à 3) : quelques
# jetons/s seulement. Le Tensor G3 (X3 + A715 + A510) supporte
# dotprod et i8mm : on les demande explicitement.
set(GGML_CPU_ARM_ARCH "armv9.2-a+dotprod+i8mm" CACHE STRING "" FORCE)

# Micro-noyaux KleidiAI (ARM) : chemins matriciels optimisés,
# surtout efficaces sur les poids Q4_0/Q8_0.
# EXPÉRIENCE du 2026-11-16 au soir (run 7) : KleidiAI ÉTEINT.
# Suspect nº 1 de la génération à ~1 t/s : KleidiAI accélère la
# lecture du prompt (matrices) mais son chemin pour la génération
# (un jeton à la fois) sur des poids Q4_K_M serait catastrophique.
# Le run 6 (KleidiAI actif) sert de témoin : lecture 34 t/s,
# génération 0,8-1 t/s, chargement 83 s (repaquetage suspecté).
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(bench_jni SHARED bench_jni.cpp)
target_link_libraries(bench_jni PRIVATE llama android log)
