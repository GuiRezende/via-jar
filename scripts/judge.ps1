param([Parameter(Mandatory=$true)][string]$Pergunta,[Parameter(Mandatory=$true)][string]$Resposta)
$provider = if ($env:VIAJAR_AI_PROVIDER) { $env:VIAJAR_AI_PROVIDER } else { 'gemini' }
$prompt = "Você é juiz independente. Compare a resposta com a solicitação de viagem. Se faltar uma parte essencial solicitada, classifique NAO_ATENDE. Se atender às partes essenciais, classifique ATENDE. Escreva exatamente duas linhas: primeira linha apenas ATENDE ou NAO_ATENDE; segunda linha uma justificativa curta coerente com o veredito. Solicitação: $Pergunta`nResposta avaliada: $Resposta"
if ($provider -eq 'gemini') {
    if (-not $env:GOOGLE_API_KEY) { throw 'Defina GOOGLE_API_KEY no ambiente.' }
    $model = if ($env:GEMINI_CHAT_MODEL) { $env:GEMINI_CHAT_MODEL } else { 'gemini-3.5-flash-lite' }
    $body = @{ contents = @(@{ parts = @(@{ text = $prompt }) }) } | ConvertTo-Json -Depth 6
    $result = Invoke-RestMethod -Uri "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent" -Method Post -Headers @{ 'x-goog-api-key' = $env:GOOGLE_API_KEY } -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    $evaluation = $result.candidates[0].content.parts[0].text
} elseif ($provider -eq 'ollama') {
    $model = if ($env:OLLAMA_CHAT_MODEL) { $env:OLLAMA_CHAT_MODEL } else { 'qwen3.5:2b' }
    $url = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { 'http://localhost:11435' }
    $body = @{model=$model;stream=$false;think=$false;options=@{num_predict=120};prompt=$prompt} | ConvertTo-Json -Depth 5
    $evaluation = (Invoke-RestMethod -Uri "$url/api/generate" -Method Post -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))).response
} else { throw 'VIAJAR_AI_PROVIDER deve ser gemini ou ollama.' }
$lines = @($evaluation.Trim() -split "`r?`n")
if ($lines.Count -lt 2 -or $lines[0].Trim() -cnotmatch '^(ATENDE|NAO_ATENDE)$' -or -not $lines[1].Trim()) {
    throw 'O Judge não retornou veredito e justificativa no formato esperado.'
}
$lines[0].Trim()
$lines[1].Trim()
