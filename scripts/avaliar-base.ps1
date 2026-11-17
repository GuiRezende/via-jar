param([string]$Url = 'http://localhost:8080')
$entrada = 'Gosto de calor'
$resultado = Invoke-RestMethod -Uri "$Url/api/chat" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes((@{mensagem=$entrada} | ConvertTo-Json)))
if ($resultado.origemResposta -ne 'BASE_CONHECIMENTO') { throw "Origem inesperada: $($resultado.origemResposta)" }
Write-Output "Origem: $($resultado.origemResposta)"
Write-Output 'Resposta da base — avaliação independente:'
& "$PSScriptRoot/judge.ps1" -Pergunta $entrada -Resposta $resultado.resposta
Write-Output 'Controle negativo — resposta sabidamente inadequada:'
& "$PSScriptRoot/judge.ps1" -Pergunta 'Quero viajar para Recife com orçamento de R$ 3000' -Resposta 'Vá para outra cidade e gaste R$ 9000.'
