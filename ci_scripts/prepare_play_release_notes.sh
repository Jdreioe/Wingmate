#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <release-tag> <play-track>" >&2
  exit 2
fi

release_tag="$1"
play_track="$2"
notes_dir="release-notes/$release_tag"
play_notes_dir="androidApp/src/main/play/release-notes"

if [[ ! "$play_track" =~ ^[a-z][a-z0-9_-]*$ ]]; then
  echo "Invalid Play track: $play_track" >&2
  exit 2
fi

if [[ ! -d "$notes_dir" ]]; then
  echo "No versioned notes found at $notes_dir; using the existing Play notes."
  exit 0
fi

shopt -s nullglob
note_files=("$notes_dir"/*.txt)
if (( ${#note_files[@]} == 0 )); then
  echo "$notes_dir must contain at least one locale file such as en-US.txt" >&2
  exit 1
fi

for note_file in "${note_files[@]}"; do
  locale="$(basename "$note_file" .txt)"
  if [[ ! "$locale" =~ ^[A-Za-z]{2,3}(-[A-Za-z]{2})?$ ]]; then
    echo "Invalid Play locale filename: $note_file" >&2
    exit 1
  fi

  character_count="$(wc -m < "$note_file")"
  if (( character_count < 1 || character_count > 500 )); then
    echo "$note_file must contain between 1 and 500 characters" >&2
    exit 1
  fi

  destination="$play_notes_dir/$locale/$play_track.txt"
  mkdir -p "$(dirname "$destination")"
  cp "$note_file" "$destination"
  echo "Prepared $destination from $note_file"
done
