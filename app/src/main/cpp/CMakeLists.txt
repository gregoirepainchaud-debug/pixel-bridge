cmake_minimum_required(VERSION 3.22)
project(bench_jni)

# llama.cpp en sous-module, épinglé par le dépôt (voir .gitmodules).
# Banc d'essai seulement : ni serveur, ni exemples, ni tests.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(bench_jni SHARED bench_jni.cpp)
target_link_libraries(bench_jni PRIVATE llama android log)
