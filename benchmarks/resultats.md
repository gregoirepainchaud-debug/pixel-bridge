# Journal des résultats — banc d'essai modèles embarqués

Une ligne par run. On n'efface jamais une ligne ; on ajoute la suivante.
Colonnes : date | job | cas | modèle (fichier exact) | harnais+version |
run | chiffres bruts | verdict d'un coup d'œil.

| Date | Job | Cas | Modèle | Harnais | Run | Résultats bruts | Verdict |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | (en attente de la ronde 1) | — |
| 2026-11-16 | extraction | D1 (sans contexte) | Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf | harnais maison, llama.cpp b12447, prompt brut sans gabarit ChatML, fils par défaut | 1 | Génération interrompue par Grégoire après ~5-6 min (règle des 5 min) ; aucune sortie produite ; le téléphone chauffait, l'app répondait | ÉCHEC — latence inacceptable ; configuration naïve condamnée, pas le concept (voir la vision consignée au GOAL le même soir) |
| 2026-11-16 | extraction | D1 (sans contexte) | Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf | harnais maison, gabarit ChatML, fils = 5, plafond 512, sortie en flux | 2 | Aucun jeton affiché après ~4 min (règle des 4 min, app fermée) ; téléphone chauffait raisonnablement ; prompt assemblé mesuré à ~650 jetons — la phase en cause (lecture du prompt ou génération) n'est pas identifiable avec ce harnais | ÉCHEC — harnais pas assez instrumenté ; prochain build : phases chronométrées affichées en direct |
| 2026-11-16 | extraction | D1 (sans contexte) | Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf | harnais instrumenté (phases + compteur), fils = 8 | 3 | Prompt de 654 jetons confirmé à l'écran ; la lecture du prompt ne se voyait pas avancer (lots atomiques de 512) et aucun « Prompt lu » rapporté après plusieurs minutes ; run déclaré échec par Grégoire, laissé en marche | ÉCHEC — cause racine trouvée le même soir dans le CMake de ggml : compilation croisée → GGML_NATIVE coupé → noyaux génériques armv8-a sans dotprod/i8mm ; correctif : GGML_CPU_ARM_ARCH=armv9.2-a+dotprod+i8mm + lots de lecture de 64 avec t/s affichée |
