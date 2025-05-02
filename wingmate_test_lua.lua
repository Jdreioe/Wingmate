local M = {}

local subscription_key = "<YOUR_AZURE_SUBSCRIPTION_KEY>"
local region = "<YOUR_AZURE_REGION>"

-- Konfig: Brug én multilingual stemme
local voice_name = "en-US-BrianMultilingualNeural"

-- Konverter tekst med [lang] tag til SSML
local function convert_multilingual_ssml(raw)
  local parts = {}
  for lang, segment in string.gmatch(raw, "%[([%w%-]+)%](.-)(?=%[|$)") do
    local ssml = string.format(
      "<lang xml:lang='%s'>%s</lang>",
      lang,
      segment
    )
    table.insert(parts, ssml)
  end

  if #parts == 0 then
    table.insert(parts, string.format("<lang xml:lang='en-US'>%s</lang>", raw))
  end

  local ssml = string.format(
    "<speak version='1.0'><voice name='%s'>%s</voice></speak>",
    voice_name,
    table.concat(parts, "")
  )

  return ssml
end

function M.speak_text(args)
  local text = args.args

  if not text or text == "" then
    local start_line = vim.fn.line("v")
    local end_line = vim.fn.line(".")
    if start_line > end_line then start_line, end_line = end_line, start_line end
    local lines = vim.api.nvim_buf_get_lines(0, start_line - 1, end_line, false)
    text = table.concat(lines, " ")
  end

  local ssml = convert_multilingual_ssml(text)

  local token_cmd = string.format(
    "curl -s -X POST \"https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken\" " ..
    "-H \"Ocp-Apim-Subscription-Key: %s\"",
    region, subscription_key
  )
  local token = vim.fn.system(token_cmd):gsub("\n", "")

  local escaped_ssml = ssml:gsub('"', '\\"'):gsub("\n", "")
  local tts_cmd = string.format(
    "curl -s -X POST \"https://%s.tts.speech.microsoft.com/cognitiveservices/v1\" " ..
    "-H \"Authorization: Bearer %s\" " ..
    "-H \"Content-Type: application/ssml+xml\" " ..
    "-H \"X-Microsoft-OutputFormat: audio-16khz-32kbitrate-mono-mp3\" " ..
    "-H \"User-Agent: nvim-azure-tts\" " ..
    "--data \"%s\" --output /tmp/tts_output.mp3",
    region, token, escaped_ssml
  )

  os.execute(tts_cmd)
  os.execute("ffplay -nodisp -autoexit /tmp/tts_output.mp3")
end

vim.api.nvim_create_user_command("TTS", M.speak_text, {
  nargs = "*",
  range = true,
  desc = "TTS with one multilingual voice and language switching",
})

return M
