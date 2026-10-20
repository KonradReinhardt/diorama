# Validação do mapa antigo — 0.4.0

## GPU: nuvens densas

Cenário `DIORAMA_TESTS=fog-gpu-bench`, executado isoladamente em Minecraft 26.3,
Fabric, JDK 25 e OpenGL, numa RTX 3070 com driver NVIDIA 616.56.
O teste usa o fragment shader real de produção em um framebuffer de 512×512,
com máscara totalmente desconhecida e a mesma textura procedural de ruído do mod.
Compara a interpolação de ruído ativada com a mesma versão do shader usando o
caminho original. Cada medida aquece 20 desenhos e cronometra 80, por consulta
de tempo da GPU; abaixo está a mediana de três pares.

| Passe Fancy de nuvens densas | Tempo por desenho |
| --- | ---: |
| Referência, ruído por camada | 0,0981376 ms |
| Interpolação das extremidades | 0,0590592 ms |
| Redução do tempo nesse passe | 39,82% |

Uma segunda execução, após os ajustes finais do shader, mediu 0,1334784 ms na
referência e 0,0899712 ms no otimizado: redução de 32,59%. Os clocks/carga da GPU
variam; as duas execuções indicam 33–40% menos tempo neste passe específico.
A comparação de imagem mediu erro RGB médio de 0,2136 em 255 e máximo de 6.
A fronteira com o terreno usa o caminho original. Isto não é uma medição de FPS
nem uma promessa desse ganho no jogo inteiro, em outras GPUs ou em outro zoom.

O primeiro protótipo do benchmark não isolava o estado de transferência de pixels
do Minecraft e caiu durante `glReadPixels` no driver. Foi corrigido para salvar,
normalizar e restaurar os buffers e parâmetros de pack/unpack e usar memória
nativa de tamanho explícito. O benchmark isolado corrigido concluiu com sucesso.
Esse código existe apenas no source set de testes e não é incluído no JAR do mod.

## Cartografia e render real

O primeiro `antique-fog` concluiu no cliente real: papel e sépia em terreno já
explorado, cartografia pixelada em 6000/6000 fora da exploração, estilo Nuvens
preservado e zoom interpolado com o ponto sob o cursor fixo em todos os frames.
Trocar somente o estilo não aumentou os jobs de terrain bake.

Duas execuções reais registraram 7709/7656 consultas de biomas, gastando
62,52/71,11 ms acumulados de amostragem, com pior consulta de 0,356/0,293 ms.
Esses custos são distribuídos pelo worker, não concentrados em um frame.
Inicialização/captura medida: 68,2 ms inicialmente e 215,7 ms após acrescentar
fingerprint completo dos registros e cópia da árvore de parâmetros.
A inicialização não faz parte do orçamento contínuo de amostragem.

A prévia não marcou como explorado o ponto 6000/64/6000. Consultas a chunks,
`getBaseHeight`, estruturas ou geração de blocos não existem no adaptador.
A reprodução fiel de costas e altura não é prometida: os dados são biomas a Y=64.

## Testes automatizados e compatibilidade

A suíte de unidades inclui cobertura negativa e CRC do cache, leitura persistida
sem repetir amostras, refinamento com reutilização das amostras grossas, seed
indisponível, isolamento entre mundos, transição/reprojeção de fog e zoom com
duração independente do FPS. Os testes existentes de persistência, scheduler,
iluminação e atualização de aparência continuam na suíte.

Resultado final local: **43 testes por alvo, zero falhas/erros**, com builds de
Fabric 26.3, Fabric 1.21.1, NeoForge 26.3 e NeoForge 1.21.1.

O cliente Fabric 26.3 concluiu em 10 min 21 s a execução combinada:

```text
DIORAMA_TESTS=antique-fog,day-night,settings-refresh,fog-of-war,saved-map
```

Ela passou a previsão em região desconhecida, zoom real, iluminação nas quatro
direções e duas qualidades, recarga de recursos, regras de fog nas cavernas,
configurações preservando cobertura e reabertura do mundo com mapa/iluminação
salvos, inclusive terreno fora da distância de carregamento. A mudança somente
de horário manteve zero novos jobs, gravações, snapshots, máscaras ou cobertura.
A captura dessa execução mediu 6894 amostras, 71,69 ms acumulados, pior consulta
0,356 ms e inicialização de 172,5 ms.

Fabric 1.21.1 também concluiu `antique-fog,settings` dentro do jogo, incluindo
renderização por `ImmediateCanvas`, pergaminho sem seed, zoom suave e layout.
Os dois builds NeoForge foram compilados e testados por unidades, sem um novo
cliente NeoForge nessa rodada.

Depois da suíte integrada, o ajuste para preservar o relógio de descoberta durante
refinamentos recebeu um novo teste de unidade; a suíte cresceu de 42 para 43.
O shader final também recebeu uma execução GPU isolada com fixture visual dos
sete símbolos, incluindo a orientação das montanhas e a grade cúbica. A suíte
integrada longa não foi repetida após esses ajustes pontuais.

JAR final Fabric 26.3: `diorama-0.4.0+26.3.jar`, 490287 bytes.
SHA-256: `7acaed49524ec3e77636bffc248b1e4299ac6bedf912dc31a9b45ee682149a91`.

## Limites da medição

Os limites de cache/arrays e texturas são explícitos. Não foi feita uma medição
de memória retida de todo o sampler vanilla para certificar o alvo de 32 MiB.
Não foi feito benchmark de trajetória longa nem medição de p95 do frame completo.
As validações locais de quatro alvos não equivalem a rodar toda a matriz de 21.

A textura de pergaminho original está em
`src/main/resources/assets/diorama/textures/map/parchment.png`, 256×256 RGB,
criada com imagegen e preparada com amostragem de borda sem transparência.
