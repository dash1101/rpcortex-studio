"""Print the release notes for a tag from CHANGELOG.md: the section headed
'## <tag>' (with or without its leading v), up to the next '## '."""
import sys


def notes(tag, text):
    want = tag.lstrip("v")
    out, on = [], False
    for line in text.splitlines():
        if line.startswith("## "):
            if on:
                break
            head = line[3:].split(",")[0].strip().lstrip("v")
            on = head == want
            continue
        if on:
            out.append(line)
    if not on and not out:
        raise SystemExit("CHANGELOG.md has no section for {}".format(tag))
    return "\n".join(out).strip() + "\n"


if __name__ == "__main__":
    with open("CHANGELOG.md", encoding="utf-8") as f:
        sys.stdout.write(notes(sys.argv[1], f.read()))
