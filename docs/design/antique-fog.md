# Mapa antigo, cartografia da seed e descoberta suave

Implementado na versão 0.4.0. O estilo padrão existente continua sendo Nuvens.
A preferência é compartilhada pelo minimapa, mapa mundial e tela do Mod Menu.

## Aparência

`Configurações → Aparência → Estilo do fog → Mapa antigo`. O terreno explorado
mantém suas próprias cores, levemente desaturadas (66% da saturação) e aquecidas.
A opção `Mapa antigo: tingir explorado` (`Settings.antiqueTintsExplored`, ligada por
padrão, vale para os dois mapas antigos) desliga esse tom: o explorado fica com as cores do
estilo de render e só o desconhecido vira mapa antigo. É só composição (`Settings.antiqueTint()`
escolhe o caminho no desenho), então trocar não refaz bake nem grava tiles; o cenário
`antique-fog` confere isso e fotografa `antique-explored-own-colours`. O mapa real conserva blocos,
construções, sombras e, no backend compatível, iluminação noturna. A mesma
gradação existe em `map_light.fsh` (Fabric 26.3) e no modo tile de `fog.fsh`
(demais backends). Marcadores mantêm suas cores. A textura
`textures/map/parchment.png` é um asset original 256×256 RGB; o alfa recebe ruído
tileável gerado no cliente, usado para bordas orgânicas sem textura extra.

Os dois estilos de fog são pixel art no estilo das texturas 16x. Cada pixel de arte
tem um pixel da GUI (a escala da GUI em pixels do framebuffer, até 4), numa grade
alinhada ao framebuffer, e não cresce ao afastar o zoom. Ao aproximar até um bloco
ficar maior que um pixel da GUI, a grade passa a ser a dos blocos do mapa, então os
pixels do fog têm o tamanho dos blocos do terreno. `MapViewport` passa o tamanho e a
fase da grade (de onde a origem do mundo cai na tela), para que ela acompanhe o mapa
em passos de pixel inteiro. Cada cor é calculada uma vez por pixel de arte.

Estilos: Nuvens, Mapa antigo (sépia, padrão do mapa antigo) e Mapa antigo colorido,
no mesmo seletor das configurações. Configurações salvas com "Mapa antigo" continuam
em sépia.

Mapa antigo:

- Fronteira: a máscara do mapa antigo tem 512 texels e é conservadora: um texel só
  conta como explorado se a sua célula de cobertura menos conhecida estiver completa
  (≥ 90%), depois do arredondamento em unidades do mapa. O contorno fica no limiar
  0,75 do campo bilinear, dentro dos texels explorados. Sobre isso, a borda é
  desfiada como a de um mapa colorido à mão (referência: Civilization VI): a parcela
  conhecida em dois raios (6 e 22 blocos, crescendo só quando o pixel de arte passa
  deles) é comparada com ruído do pergaminho ancorado no mapa e misturado suavemente
  entre escalas, então a borda recua em lóbulos largos e entalhes pequenos, deixa
  bolsões de névoa e mantém a mesma forma ao dar zoom. Ela só recua para dentro, nunca descobre terreno sem dados. Uma
  linha de tinta de um pixel, duas faixas de papel queimado e uma sombra de tinta de
  um pixel para dentro acompanham a borda desfiada.
- Sépia: preenchimentos ficam na metade clara de uma rampa de umber a pergaminho, como
  aguadas sobre papel; costas e sprites usam a rampa inteira, como traços de tinta. A
  água fica um pouco mais escura que a terra. A variante colorida usa a paleta de biomas.
- Biomas: preenchimentos chapados por classe, com uma sombra de um pixel nas bordas
  entre classes; a classe de cada ponto é a de maior peso entre os quatro texels
  vizinhos. No CPU, cada texel pondera bilinearmente as quatro amostras da prévia,
  num ponto deslocado por ruído ancorado no mundo, então as bordas são curvas.
- Água: rasa e funda pelo campo de água, linha tracejada ao largo, espuma de um pixel
  do lado da água e contorno escuro de um pixel do lado da terra, só onde os dois
  lados têm previsão.
- Terra sem ícones: os biomas aparecem só como aguadas, com a sombra de um pixel nas
  bordas entre classes. O mar mantém seus detalhes: espuma, tracejado ao largo e um
  sprite 16×16 de marolas (`docs/design/antique-sprites.py`) em pontos do mundo
  espaçados por uma potência de dois de unidades (pelo menos 20 pixels de arte).
- Papel: as manchas do pergaminho em três tons chapados.

Nuvens: a altura das nuvens em cada pixel de arte vem do ruído e da cobertura
conhecida, desenhada como puffs em três degraus de tom chapado. Um degrau para baixo
ao sul é a parte de baixo sombreada do puff de cima; para o norte, a borda iluminada.
As nuvens projetam uma sombra pontilhada (um pixel sim, um não) a sudeste, sobre o
mapa. O tom acompanha a luz do dia e as cavernas usam sua própria paleta. O antigo
shader volumétrico de oito camadas e a sua otimização foram substituídos.

Um passe opaco do mesmo shader é desenhado antes dos tiles (coordenada u deslocada
em 16, decodificada no shader). Onde o terreno tem buracos, aparece o fog, e não uma
cor lisa. No mapa antigo, o passe sobre os tiles só desenha perto da fronteira: flags
na máscara marcam texels sem nenhum terreno por perto (pela cobertura bruta) e texels
totalmente explorados. Nas nuvens, cada ponto lido consulta as flags do próprio texel,
então só o desfoque da cobertura conta no alcance delas. Trocar o nível de detalhe da
máscara ao dar zoom não anima a descoberta, e mudanças só de cobertura reconstroem a
máscara no máximo a cada 40 ms. As Nuvens mantêm a máscara de 256 texels.

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

Cada bioma é classificado pelas tags `minecraft:` e `c:` (oceano, montanha, floresta,
deserto, neve, pântano) e, sem tag, pelo nome. Assim biomas de datapacks e mods que
declaram as tags comuns caem na classe certa. A amostragem consulta biomas na altura 64. Biomas e litoral real podem divergir:
não é uma previsão exata da altura, rios, praias ou terreno de chunks gerados por
versões anteriores. Não consulta `getBaseHeight`, estruturas, blocos ou luz, nem
solicita geração/carregamento de chunks. A seed com hash do multiplayer não é
tratada como seed original. O mapa observado sempre substitui a previsão.

## Custos e cancelamento

- Regiões de 4096×4096 blocos: primeiro 16×16 amostras, passo 256. Com zoom de
  pelo menos 0,15, refina até 64×64, passo 64, e com zoom de pelo menos 0,45 até
  128×128, passo 32, reutilizando sempre as amostras anteriores.
- Um worker de prioridade mínima e uma região ativa por vez. Processa no máximo
  16 regiões do viewport, priorizando o centro; não percorre o mundo inteiro.
- Movimento contínuo espera 250 ms. Alterar o viewport cancela a região corrente
  antes da próxima amostra. Pausas interrompíveis de até 20 ms mantêm a saída do
  mundo e as mudanças de destino responsivas.
- Fatias de aproximadamente 2 ms, seguidas de pausa de 9 vezes o tempo gasto:
  alvo de 100 ms de amostragem por segundo numa thread de prioridade mínima. É cooperativo: uma chamada individual
  pode ultrapassar a fatia; acima de 50 ms (máquina ocupada com bakes, geração ou GC)
  o worker descansa 1 s e continua. A previsão não é mais desligada pelo resto da sessão.
  A criação do sampler, codecs e cache em disco são custos separados da amostragem.
- Cache do worker e cache do render com até 960 referências e 4 MiB de amostras
  cada, descartando as menos usadas. Compartilham arrays imutáveis; no pior caso de
  conjuntos diferentes, os arrays somam menos de 8 MiB. Sem filas de jobs
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
uma transição ease-out cúbica de 420 ms nos dois estilos, com 64 passos de opacidade.
Cada chegada de tile reinicia o relógio a partir do estado exibido; com smoothstep
(velocidade zero no início) os reinícios frequentes ao abrir o mapa congelavam a
revelação, enquanto a ease-out continua avançando a cada reinício.
Trocar de nível de detalhe (overviews ↔ tiles detalhados) reinicia o `FogReveal` sem animar, como
trocar o texel da máscara: os dois mudam em zooms diferentes, e animar a troca revelava a fronteira de
novo a cada zoom. Enquanto um tile do nível mostrado não está na GPU, a máscara empresta a cobertura
e o terreno dos tiles residentes de outro nível (o de baixo primeiro, depois os overviews acima); nem
o empréstimo nem o tile que o substitui são animados. Só tiles residentes emprestam, então cobertura
descartada pelo mapa (fog of war, outra pasta) não volta. Afastado (overviews), nada é animado: a
cobertura ali só muda quando um overview é refeito a partir de tiles já mostrados de perto.
Só aumentos de cobertura são animados. Reduções escondem imediatamente os dados;
alterar camada, mundo ou direção descarta o histórico anterior. Mover o viewport
reprojeta o histórico existente, evitando reiniciar a descoberta durante um pan.
A animação não solicita terrain bakes nem uploads de máscara por frame.

`SmoothZoom` acumula a roda em um destino e interpola o logaritmo do zoom com tempo
real. A constante de 75 ms produz uma aproximação rápida, sem saltos por tick,
preservando o ponto sob o cursor em todos os frames. Arrastar ou recentrar cancela
a animação pendente; cavernas e mapas sem overviews mantêm seu limite de zoom.

## Otimizações de render

Pixels inteiramente revelados ou sem nada a desenhar descartam o fragmento cedo, pelas
flags da máscara. A máscara pré-calcula suas colunas e a descoberta é interpolada pela
GPU. Alterar estilo é composição, sem reprocessar os pixels de terrain bake nem apagar
mapas salvos. O teste `fog-gpu-bench` foi removido junto com o shader volumétrico que
ele media. O teste `antique-fog` cobre shaders reais dos dois estilos, previsão em região
desconhecida, separação da exploração, troca de estilo sem novos bakes e ancoragem do zoom.
Resultados medidos ficam em [docs/testing/antique-fog.md](../testing/antique-fog.md).
