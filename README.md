# Activation API

Serviço backend para registrar solicitações de ativação de produtos, consultar
seus estados e realizar a ativação por meio de um provedor externo.

## Tecnologias

- Java 21
- Spring Boot 4.1.2
- Maven Wrapper
- Spring MVC
- Spring Data JPA / Hibernate
- PostgreSQL 17
- Flyway
- Spring Security OAuth2 Resource Server
- Spring Boot Actuator
- JUnit, Mockito e AssertJ
- Testcontainers
- Docker Compose
- WireMock para simular o provedor

## Pré-requisitos

- Java 21
- Docker Engine e Docker Compose
- Acesso à internet para baixar dependências Maven e imagens Docker

Não é necessário instalar o Maven separadamente.

## Executando os testes

Na raiz do projeto:

```bash
chmod +x mvnw
./mvnw verify
```

O Docker deve estar disponível, pois os testes de integração iniciam um
PostgreSQL isolado por meio do Testcontainers.

Os testes automatizados não utilizam o banco de desenvolvimento.

## Executando com Docker Compose

### 1. Gerar o token de desenvolvimento

```bash
./mvnw -DskipTests test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=br.com.rafaelmacedo.activation_api.support.LocalTokenGenerator \
  -Dexec.classpathScope=test
```

O comando gera:

- `target/local-auth/access-token.txt`: token válido por uma hora.
- `target/local-auth/public/jwks.json`: chave pública utilizada na validação.

A chave privada é gerada em memória e não é salva em disco.

Gere esses arquivos antes de iniciar o Docker Compose. O comando `mvn clean`
remove o diretório `target`, exigindo que os arquivos sejam gerados novamente.

### 2. Iniciar o ambiente

```bash
docker compose up -d --build
```

O ambiente contém:

| Serviço | Endereço local |
| --- | --- |
| Aplicação | http://localhost:8080 |
| Mock do provedor | http://localhost:9090 |
| PostgreSQL | localhost:5435 |

Confira a inicialização:

```bash
docker compose ps
docker compose logs --tail=50 app
```

Após a aplicação terminar de iniciar, consulte o health:

```bash
curl -i http://localhost:8080/actuator/health
```

Resultado esperado: HTTP 200 com `"status":"UP"`.

### 3. Carregar o token

```bash
ACTIVATION_TOKEN=$(cat target/local-auth/access-token.txt)
```

O token local possui:

- Subject: `local-consumer`
- Audience: `activation-api`
- Escopos: `requests:read requests:write`

### Renovar o token local

Execute novamente o gerador de token e reinicie os serviços:

```bash
docker compose restart provider app
```

Carregue novamente a variável `ACTIVATION_TOKEN` e aguarde a aplicação iniciar.

O gerador de token e o endpoint JWKS local são ferramentas de desenvolvimento.
Eles não representam um servidor de autorização para produção.

### Encerrar o ambiente

```bash
docker compose down
```

O volume de dados do PostgreSQL é preservado.

## Utilizando a API

### Criar uma solicitação

```bash
curl -i http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $ACTIVATION_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "externalId": "EXT-EXAMPLE-001",
    "customerId": "CUST-10001",
    "productCode": "INTERNET-500"
  }'
```

Uma nova solicitação retorna HTTP 201 e um header `Location` com sua URL
de consulta.

O processamento é assíncrono. A resposta de criação normalmente apresenta
`PENDING`; consulte a solicitação para acompanhar o estado atual.

### Consultar uma solicitação

Utilize o identificador retornado na criação:

```bash
REQUEST_ID="substitua-pelo-id-retornado"

curl -i "http://localhost:8080/api/v1/requests/$REQUEST_ID" \
  -H "Authorization: Bearer $ACTIVATION_TOKEN"
```

### Regras de validação

Os campos `externalId`, `customerId` e `productCode`:

- São obrigatórios.
- Não podem estar vazios nem conter apenas espaços.
- Devem possuir no máximo 100 caracteres.

Os identificadores não são automaticamente normalizados nem têm seus espaços
removidos.

O identificador do consumidor é obtido do subject do JWT, não do JSON enviado.

### Respostas

| Cenário | Resposta |
| --- | --- |
| Nova solicitação | 201 Created, com Location |
| Mesmo externalId, consumidor e conteúdo | 200 OK, com o registro existente |
| Mesmo externalId com conteúdo diferente | 409 Conflict |
| externalId pertencente a outro consumidor | 409 Conflict, sem exposição do registro |
| Consulta de solicitação do consumidor | 200 OK |
| Registro inexistente ou de outro consumidor | 404 Not Found |
| Dados inválidos ou JSON malformado | 400 Bad Request |
| Autenticação ausente ou inválida | 401 Unauthorized |
| Escopo insuficiente | 403 Forbidden |

Repetir uma solicitação não reinicia sua ativação, inclusive quando ela já
estiver em `COMPLETED` ou `FAILED`.

## Processamento e integração com o provedor

Um scheduler consulta até dez solicitações pendentes elegíveis por lote.

Cada ativação possui três etapas:

1. Reservar a solicitação em uma transação curta.
2. Chamar o provedor fora da transação do banco.
3. Persistir o resultado em outra transação curta.

O endpoint do provedor é:

```text
POST /provider/v1/activate
```

O UUID persistido da solicitação é enviado no header `Idempotency-Key`
e permanece igual em todas as tentativas.

| Resultado do provedor | Comportamento |
| --- | --- |
| 2xx | Alterar para COMPLETED |
| 408, 429 ou 5xx | Agendar nova tentativa, se permitido |
| Timeout ou falha de conexão | Agendar nova tentativa, se permitido |
| Demais respostas HTTP | Alterar para FAILED |
| Limite de tentativas atingido | Alterar para FAILED |

O limite padrão é de três tentativas, incluindo a inicial.

O backoff exponencial começa em dois segundos e possui limite local de um
minuto. Um `Retry-After` posterior ao horário calculado, recebido em respostas
429 ou 503, tem prioridade.

A execução pode ocorrer depois do horário calculado, pois depende do scheduler
e do processamento sequencial do lote.

### Comportamento do mock local

- `productCode` igual a `INVALID`: retorna HTTP 400.
- Outros produtos, com o header de idempotência esperado: retornam HTTP 204.

Utilize um novo `externalId` ao testar um conteúdo diferente.

O mock simula respostas HTTP. Ele não realiza ativações nem implementa
deduplicação no lado do provedor.

## Decisões técnicas

### Prevenção de duplicidade

Uma constraint única sobre `external_id` garante a unicidade no banco,
inclusive em inserções concorrentes.

O serviço consulta previamente o identificador para tratar repetições comuns.
Se outra requisição inserir o registro durante essa disputa, a transação da
inserção rejeitada é desfeita antes de consultar o registro existente.

Nesta implementação, `externalId` é globalmente único.

### Estados e concorrência

O domínio controla as transições:

```text
PENDING -> PROCESSING -> COMPLETED
                     -> FAILED
                     -> PENDING (retry)
```

Estados finais não podem ser reiniciados pelos métodos do domínio.

O lock otimista com `@Version` protege atualizações concorrentes. O número
da tentativa também é verificado antes de aplicar o resultado, impedindo
que uma tentativa antiga finalize uma mais recente.

### Persistência

O Flyway controla a criação e a evolução do schema.

O Hibernate utiliza `ddl-auto=validate` e não cria nem altera tabelas.

As datas utilizam `Instant` e colunas PostgreSQL com fuso horário.

### Cliente HTTP

Os timeouts de conexão e de requisição são configurados explicitamente.

O cliente do provedor utiliza HTTP/1.1. Durante a validação no Docker, a
comunicação HTTP/2 com o mock apresentou erros de EOF e cancelamento de stream.
A configuração explícita de HTTP/1.1 resolveu a falha observada.

### Tratamento de erros

Os erros da aplicação utilizam `ProblemDetail`.

Erros inesperados retornam uma mensagem genérica ao consumidor e são
registrados no servidor. Os filtros do Spring Security tratam falhas de
autenticação e autorização.

## Segurança

A aplicação funciona como OAuth2 Resource Server. Ela não emite tokens de
produção nem implementa um servidor de autorização.

A validação do JWT inclui:

- Verificação da assinatura pela chave pública do JWKS configurado.
- Emissor esperado.
- Audiência esperada.
- Presença e validação da expiração.
- Validação de `nbf`, quando informado.
- Subject obrigatório, não vazio e com no máximo 100 caracteres.

A autorização exige:

| Operação | Escopo |
| --- | --- |
| Criar solicitação | requests:write |
| Consultar solicitação | requests:read |

O subject identifica o consumidor. As consultas incluem esse identificador
para impedir o acesso a solicitações de outros consumidores.

Em produção, um provedor de identidade externo pode emitir tokens utilizando
OAuth2 Client Credentials para comunicação entre sistemas, respeitando o
contrato de subject, audiência e escopos.

Os endpoints de health são públicos. Os demais endpoints não autorizados
explicitamente são bloqueados.

O cliente do provedor aceita uma credencial opcional no header `X-API-Key`,
configurada por `PROVIDER_API_KEY`. Em produção, ela deve ser fornecida pelo
ambiente de implantação ou por um gerenciador de segredos, com procedimento
de rotação definido junto ao provedor.

## Configurações principais

| Variável | Padrão fora do Docker |
| --- | --- |
| DB_URL | jdbc:postgresql://localhost:5435/activation_db |
| DB_USERNAME | activation_user |
| DB_PASSWORD | activation_password |
| PROVIDER_BASE_URL | http://localhost:9090 |
| PROVIDER_CONNECT_TIMEOUT | 2s |
| PROVIDER_READ_TIMEOUT | 5s |
| PROVIDER_API_KEY | Vazio |
| PROCESSING_ENABLED | false |
| PROCESSING_POLL_DELAY | 1000 milissegundos |
| PROCESSING_MAX_ATTEMPTS | 3 |
| PROCESSING_INITIAL_BACKOFF | 2s |
| PROCESSING_MAX_BACKOFF | 1m |
| JWT_ISSUER | http://localhost:9000 |
| JWT_AUDIENCE | activation-api |
| JWT_JWK_SET_URI | http://localhost:9000/oauth2/jwks |

O Compose substitui os endereços do banco e do provedor, habilita o
processamento e configura o JWKS de desenvolvimento.

Para executar a aplicação fora do Docker, configure um emissor/JWKS acessível
e execute:

```bash
PROCESSING_ENABLED=true ./mvnw spring-boot:run
```

## Estratégia de testes

### Testes unitários

Validam transições de domínio, cálculo de retries e decisões do serviço,
sem iniciar Spring ou banco.

O Mockito simula colaboradores do serviço. Relógios fixos tornam
determinísticas as regras dependentes de horário.

### Testes de integração

Utilizam PostgreSQL com Testcontainers e aplicam a migration real do Flyway.

Cobrem persistência, unicidade com inserções simultâneas, lock otimista,
validação da API, isolamento entre consumidores e autorização.

Os testes de JWT utilizam tokens assinados com RSA e um servidor JWKS local,
exercitando o decoder real.

Os testes do provedor utilizam um servidor HTTP local para verificar payload,
headers, classificação das respostas, Retry-After e timeout.

Os testes de processamento combinam banco, serviços transacionais e um
provedor HTTP para validar ativação e novas tentativas.

### Verificação manual de ponta a ponta

O ambiente Docker Compose foi utilizado para validar criação autenticada,
processamento agendado, comunicação com o provedor e consulta de status,
tanto para ativação concluída quanto para rejeição do produto.

A suíte automatizada atual não inicia todo o ambiente Compose como um teste
de ponta a ponta.

## Análise de lentidão em produção

Considerando o aumento da latência da API de 300 ms para 2,5 segundos, das
consultas de 50 ms para 1,2 segundo e do provedor de 400 ms para 3 segundos:

1. Identificar quando a degradação começou e correlacionar com deploys,
   mudanças de tráfego e incidentes em dependências.
2. Comparar percentis de latência, taxa de erros e volume por endpoint.
3. Utilizar traces para separar tempo de aplicação, banco e chamadas externas.
4. Inspecionar consultas lentas, planos de execução, índices, locks e espera
   por conexões.
5. Verificar latência do provedor, timeouts, retries e saturação.
6. Analisar threads, CPU, memória, garbage collection e limites dos contêineres.

CPU e memória moderadas não descartam espera por I/O, locks ou conexões.

Uma possível causa comum é o aumento de tráfego ou de retries, sobrecarregando
banco e provedor. Em outras arquiteturas, chamadas remotas dentro de transações
abertas também podem reter conexões e ampliar a contenção. Essas hipóteses
devem ser verificadas com traces, métricas do pool e duração das transações.

As melhorias dependem das evidências: ajustes de consultas e índices,
transações mais curtas, concorrência limitada, timeouts adequados e retries
controlados.

Após publicar a correção, comparar percentis de latência, volume, falhas,
espera por conexões e backlog com a referência anterior, sob carga comparável.
Também confirmar a preservação da integridade e da taxa de sucesso das ativações.

## Execução em produção

O Dockerfile utiliza build em múltiplos estágios e uma imagem de execução
com Java 21 JRE e usuário não-root.

Para produção:

- Implantar a imagem da aplicação com PostgreSQL externo.
- Configurar um provedor de identidade e um provedor de ativação reais.
- Fornecer credenciais por gerenciamento de segredos.
- Utilizar HTTPS e a autenticação exigida pelo provedor.
- Configurar limites de memória, CPU e orçamento de conexões.
- Centralizar logs e monitorar health, backlog e solicitações com falha.
- Respeitar o desligamento gracioso e configurar probes de saúde.
- Executar os testes antes de publicar a imagem.
- Coordenar as migrations durante a implantação.

O Compose fornecido e o gerador de tokens destinam-se ao desenvolvimento
e à avaliação do desafio.

## Limitações e melhorias futuras

- Solicitações interrompidas em PROCESSING não são recuperadas automaticamente.
  Uma evolução deve implementar lease de processamento e reconciliação,
  protegendo também contra resultados atrasados.
- Um timeout pode ocorrer após a ativação no provedor. A repetição segura
  depende de idempotência no provedor ou reconciliação; o serviço sozinho
  não garante efeitos externos exatamente uma vez.
- Os lotes são processados sequencialmente. Maior vazão exige concorrência
  limitada e planejamento de capacidade.
- O backoff não possui jitter.
- Falhas de autenticação no provedor são tratadas como definitivas e exigem
  investigação operacional.
- Listagem paginada e filtros não foram implementados.
- Tracing distribuído, identificador de correlação, métricas de negócio
  específicas e logs JSON não foram implementados.
- A dependência Prometheus está presente, mas seu endpoint não está exposto.
- Rate limiting, limite explícito de corpo da requisição, pipeline CI/CD
  e testes de arquitetura não foram implementados.

## Uso de IA

Ferramentas de IA foram utilizadas na discussão das decisões técnicas,
elaboração de código e testes, investigação de falhas de integração e
preparação da documentação.

A implementação foi compilada e testada localmente. A execução no Docker
também foi validada com requisições HTTP autenticadas.