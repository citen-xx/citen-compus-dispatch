$ErrorActionPreference = "Stop"

$projectRoot = (Get-Location).Path
$contentPath = Join-Path $projectRoot "tmp/docs/Introduction_content.txt"
$outputPath = Join-Path $projectRoot "Introduction.docx"
$tempRoot = Join-Path $projectRoot "tmp/docs/introduction_docx_build"
$zipPath = Join-Path $projectRoot "tmp/docs/introduction_docx_build.zip"

if (-not (Test-Path $contentPath)) {
    throw "Content file not found: $contentPath"
}

if (Test-Path $tempRoot) {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}
if (Test-Path $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
if (Test-Path $outputPath) {
    Remove-Item -LiteralPath $outputPath -Force
}

New-Item -ItemType Directory -Path $tempRoot | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tempRoot "_rels") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tempRoot "docProps") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tempRoot "word") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tempRoot "word/_rels") | Out-Null

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Utf8File {
    param(
        [string]$Path,
        [string]$Content
    )
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Escape-XmlText {
    param([string]$Text)
    if ($null -eq $Text) {
        return ""
    }
    return $Text.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;")
}

$paragraphs = New-Object System.Collections.Generic.List[object]
$lines = Get-Content -LiteralPath $contentPath -Encoding UTF8

foreach ($line in $lines) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    $parts = $line.Split("|", 2)
    if ($parts.Count -lt 2) {
        continue
    }
    $style = $parts[0].Trim()
    $text = $parts[1]
    $align = if ($style -eq "TITLE" -or $style -eq "SUBTITLE") { "center" } else { "left" }
    $paragraphs.Add([PSCustomObject]@{
        Text  = $text
        Style = $style
        Align = $align
    }) | Out-Null
}

function Convert-ParagraphToXml {
    param($Paragraph)

    $style = $Paragraph.Style
    $align = $Paragraph.Align
    $text = Escape-XmlText $Paragraph.Text

    $fontAscii = "Calibri"
    $fontEastAsia = "宋体"
    $size = "22"
    $bold = $false
    $spacingBefore = "0"
    $spacingAfter = "80"

    switch ($style) {
        "TITLE" {
            $size = "36"
            $bold = $true
            $spacingBefore = "120"
            $spacingAfter = "220"
            $fontEastAsia = "微软雅黑"
        }
        "SUBTITLE" {
            $size = "22"
            $spacingAfter = "180"
            $fontEastAsia = "微软雅黑"
        }
        "H1" {
            $size = "30"
            $bold = $true
            $spacingBefore = "100"
            $spacingAfter = "120"
            $fontEastAsia = "微软雅黑"
        }
        "H2" {
            $size = "26"
            $bold = $true
            $spacingBefore = "80"
            $spacingAfter = "100"
            $fontEastAsia = "微软雅黑"
        }
        "H3" {
            $size = "23"
            $bold = $true
            $spacingBefore = "60"
            $spacingAfter = "70"
        }
        "NOTE" {
            $size = "20"
            $spacingAfter = "120"
        }
        default {
            $size = "22"
        }
    }

    $jc = if ($align -eq "center") { "center" } else { "left" }
    $boldXml = if ($bold) { "<w:b/><w:bCs/>" } else { "" }

    return @"
<w:p>
  <w:pPr>
    <w:jc w:val="$jc"/>
    <w:spacing w:before="$spacingBefore" w:after="$spacingAfter" w:line="360" w:lineRule="auto"/>
  </w:pPr>
  <w:r>
    <w:rPr>
      <w:rFonts w:ascii="$fontAscii" w:hAnsi="$fontAscii" w:eastAsia="$fontEastAsia" w:cs="$fontAscii"/>
      $boldXml
      <w:sz w:val="$size"/>
      <w:szCs w:val="$size"/>
    </w:rPr>
    <w:t xml:space="preserve">$text</w:t>
  </w:r>
</w:p>
"@
}

$bodyXml = ($paragraphs | ForEach-Object { Convert-ParagraphToXml $_ }) -join "`n"

$documentXml = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:o="urn:schemas-microsoft-com:office:office"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"
            xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:w10="urn:schemas-microsoft-com:office:word"
            xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"
            xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
            xmlns:wpi="http://schemas.microsoft.com/office/word/2010/wordprocessingInk"
            xmlns:wne="http://schemas.microsoft.com/office/word/2006/wordml"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            mc:Ignorable="w14 wp14">
  <w:body>
$bodyXml
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>
      <w:cols w:space="708"/>
      <w:docGrid w:linePitch="360"/>
    </w:sectPr>
  </w:body>
</w:document>
"@

$stylesXml = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="宋体" w:cs="Calibri"/>
        <w:sz w:val="22"/>
        <w:szCs w:val="22"/>
        <w:lang w:val="zh-CN" w:eastAsia="zh-CN" w:bidi="en-US"/>
      </w:rPr>
    </w:rPrDefault>
    <w:pPrDefault>
      <w:pPr>
        <w:spacing w:after="80" w:line="360" w:lineRule="auto"/>
      </w:pPr>
    </w:pPrDefault>
  </w:docDefaults>
</w:styles>
"@

$contentTypesXml = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>
"@

$relsXml = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>
"@

$documentRelsXml = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>
"@

$now = [DateTime]::UtcNow.ToString("s") + "Z"

$coreXml = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                   xmlns:dc="http://purl.org/dc/elements/1.1/"
                   xmlns:dcterms="http://purl.org/dc/terms/"
                   xmlns:dcmitype="http://purl.org/dc/dcmitype/"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>HM点评微服务项目介绍与面试手册</dc:title>
  <dc:subject>简历项目介绍</dc:subject>
  <dc:creator>Codex</dc:creator>
  <cp:keywords>Spring Cloud, Redis, RabbitMQ, Gateway, Nacos, OpenFeign</cp:keywords>
  <dc:description>用于投简历和面试准备的项目说明文档</dc:description>
  <cp:lastModifiedBy>Codex</cp:lastModifiedBy>
  <dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>
</cp:coreProperties>
"@

$appXml = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
            xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
  <Application>Microsoft Office Word</Application>
  <DocSecurity>0</DocSecurity>
  <ScaleCrop>false</ScaleCrop>
  <HeadingPairs>
    <vt:vector size="2" baseType="variant">
      <vt:variant>
        <vt:lpstr>Title</vt:lpstr>
      </vt:variant>
      <vt:variant>
        <vt:i4>1</vt:i4>
      </vt:variant>
    </vt:vector>
  </HeadingPairs>
  <TitlesOfParts>
    <vt:vector size="1" baseType="lpstr">
      <vt:lpstr>HM点评微服务项目介绍与面试手册</vt:lpstr>
    </vt:vector>
  </TitlesOfParts>
  <Company>OpenAI</Company>
  <LinksUpToDate>false</LinksUpToDate>
  <SharedDoc>false</SharedDoc>
  <HyperlinksChanged>false</HyperlinksChanged>
  <AppVersion>16.0000</AppVersion>
</Properties>
"@

Write-Utf8File -Path (Join-Path $tempRoot "[Content_Types].xml") -Content $contentTypesXml
Write-Utf8File -Path (Join-Path $tempRoot "_rels/.rels") -Content $relsXml
Write-Utf8File -Path (Join-Path $tempRoot "docProps/core.xml") -Content $coreXml
Write-Utf8File -Path (Join-Path $tempRoot "docProps/app.xml") -Content $appXml
Write-Utf8File -Path (Join-Path $tempRoot "word/document.xml") -Content $documentXml
Write-Utf8File -Path (Join-Path $tempRoot "word/styles.xml") -Content $stylesXml
Write-Utf8File -Path (Join-Path $tempRoot "word/_rels/document.xml.rels") -Content $documentRelsXml

Compress-Archive -Path (Join-Path $tempRoot "*") -DestinationPath $zipPath -Force
Move-Item -LiteralPath $zipPath -Destination $outputPath -Force

Write-Host "Generated: $outputPath"
