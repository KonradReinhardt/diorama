# Mapa antigo, cartografia da seed e descoberta suave

Implementado na versão 0.4.0. O estilo padrão existente continua sendo Nuvens.
A preferência é compartilhada pelo minimapa, mapa mundial e tela do Mod Menu.

## Aparência

`Configurações → Aparência → Estilo do fog → Mapa antigo` mantém a textura e a
paleta de pergaminho em toda a superfície, inclusive no terreno já explorado.
O mapa real conserva seus blocos, construções, sombras e, no backend compatível,
iluminação noturna, convertidos para sépia na composição. Marcadores mantêm suas
cores para continuar legíveis. A textura `textures/map/parchment.png` é um asset
original gerado com imagegen, reduzido para 256×256 RGB, sem transparência.

O desconhecido mostra papel e tinta marrom: costas em degraus e símbolos de
árvores, montanhas, neve e água. O desenho usa uma grade de pixels ancorada no
mapa. Os símbolos representam classes de biomas, não árvores ou estruturas reais.
Não há deslocamento aleatório das costas, curvas suavizadas ou relevo inventado.
A textura do papel permanece estática no mundo; a descoberta muda a opacidade,
sem arredondar a grade do desenho. O estilo Nuvens conserva sua aparência própria.

## Cartografia prevista

A implementação inicial consulta somente biomas do Overworld no singleplayer de
Minecraft 26.3, em Fabric e NeoForge. Precisa de um `NoiseBasedChunkGenerator` e
uma `MultiNoiseBiomeSource` compatíveis. Mundos sem esse contexto, multiplayer,
cavernas, Nether, End e versões anteriores continuam mostrando pergaminho.
A opção Cartografia da seed permite desativar a previsão sem trocar o estilo.

`SeedBiomeSource` captura a seed, registros congelados e configuração do gerador
no servidor integrado. O worker cria seu próprio `RandomState`, resolver e árvore
de busca dos parâmetros de biomas. Não usa os caches do gerador ativo. A captura
é uma tarefa de entrada, não uma consulta por amostra ou por frame. O worker pode
esperar essa captura; a thread de render nunca espera.

A amostragem consulta biomas na altura 64. Biomas e litoral real podem divergir:
não é uma previsão exata da altura, rios, praias ou terreno de chunks gerados por
versões anteriores. Não consulta `getBaseHeight`, estruturas, blocos ou luz, nem
solicita geração/carregamento de chunks. A seed com hash do multiplayer não é
tratada como seed original. O mapa observado sempre substitui a previsão.

## Custos e cancelamento

- Regiões de 4096×4096 blocos: primeiro 16×16 amostras, passo 256. Com zoom de
  pelo menos 0,15, refina até 64×64, passo 64, reutilizando as amostras iniciais.
- Um worker de prioridade mínima e uma região ativa por vez. Processa no máximo
  16 regiões do viewport, priorizando o centro; não percorre o mundo inteiro.
- Movimento contínuo espera 250 ms. Alterar o viewport cancela a região corrente
  antes da próxima amostra. Pausas interrompíveis de até 20 ms mantêm a saída do
  mundo e as mudanças de destino responsivas.
- Fatias de aproximadamente 2 ms, seguidas de pausa de 49 vezes o tempo gasto:
  alvo de 20 ms de amostragem por segundo. É cooperativo: uma chamada individual
  pode ultrapassar a fatia; acima de 50 ms a previsão é interrompida e fica papel.
  A criação do sampler, codecs e cache em disco são custos separados da amostragem.
- Cache do worker e cache do render com até 960 referências cada. Compartilham
  arrays imutáveis. Cada conjunto referencia menos de 4 MiB de amostras; no pior
  caso de conjuntos diferentes, os arrays somam menos de 8 MiB. Sem filas de jobs
  ilimitadas ou texturas GPU individuais por região.
- Cache em disco independente, checksums e publicação atômica. Limite aproximado
  de 64 MiB por dimensão, com limpeza na abertura e a cada 16 gravações. Arquivos
  temporários não contam como resultados. Erros de cache permitem recalcular.
- A identidade inclui seed, versão, algoritmo, fonte de biomas, settings e conteúdo
  dos registros de ruído, densidade e parâmetros. Alterar um datapack referenciado
  invalida a previsão mesmo se os nomes dos registros continuarem iguais.
- O render consulta somente dados prontos. Um cursor reutiliza a região da linha
  durante a rasterização. Após completar um viewport parado, não há amostragem
  adicional, leituras de disco por frame ou uploads contínuos de máscaras.

A previsão usa os canais livres da máscara do próprio fog. A GPU acrescenta apenas
uma textura compartilhada de papel de 256 KiB, sem um atlas crescente de regiões.
Cada viewport conserva uma máscara RGBA de 256 KiB. O estilo usa o mesmo pipeline
com dois samplers, inclusive nos backends antigos. Não escreve `.omt`/`.omt2`,
não chama `markSeen` e não incrementa a exploração.

Os limites das amostras e texturas são explícitos; o alvo original de 32 MiB para
RAM adicional total, incluindo o sampler vanilla, ainda precisa de uma medição
específica de memória retida. Não é apresentado como um teto já comprovado.

## Descoberta e zoom

`FogReveal` conserva a cobertura exibida e a cobertura de destino. O shader aplica
uma transição smoothstep de 600 ms nos dois estilos, com 64 passos de opacidade.
Só aumentos de cobertura são animados. Reduções escondem imediatamente os dados;
alterar camada, mundo ou direção descarta o histórico anterior. Mover o viewport
reprojeta o histórico existente, evitando reiniciar a descoberta durante um pan.
A animação não solicita terrain bakes nem uploads de máscara por frame.

`SmoothZoom` acumula a roda em um destino e interpola o logaritmo do zoom com tempo
real. A constante de 75 ms produz uma aproximação rápida, sem saltos por tick,
preservando o ponto sob o cursor em todos os frames. Arrastar ou recentrar cancela
a animação pendente; cavernas e mapas sem overviews mantêm seu limite de zoom.

## Otimizações de render

Pixels inteiramente revelados pulam o shader de nuvens. Nas nuvens densas Fancy,
o ruído vertical é interpolado entre as duas extremidades, também na sonda solar.
A fronteira com o terreno mantém o caminho original. A máscara pré-calcula suas
colunas e a descoberta é interpolada pela GPU. Alterar estilo é composição,
sem reprocessar os pixels de terrain bake nem apagar mapas salvos.

O teste `fog-gpu-bench` compara o mesmo shader com essa otimização ativada e
inativa, usando consultas de tempo da GPU e verificando a diferença de imagem.
Ele mede somente o passe de nuvens densas de 512×512, não o FPS do jogo inteiro.
O teste `antique-fog` cobre shaders reais, previsão em região desconhecida,
separação da exploração, troca de estilo sem novos bakes e ancoragem do zoom.
Resultados medidos ficam em [docs/testing/antique-fog.md](../testing/antique-fog.md).
