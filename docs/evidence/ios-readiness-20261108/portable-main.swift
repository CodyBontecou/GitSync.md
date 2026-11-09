import Foundation

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    precondition(condition(), message)
    checks += 1
}
for name in ["bad/name", "bad\\name", "bad:name", "bad\tname", "bad\nname", "bad\0name", "\tname", "name\r", "bad\u{7f}name", ".", "..", String(repeating: "é", count: 128)] {
    check(RepositoryItemName.validationMessage(name) != nil, "Must reject invalid filename")
}
for name in ["normal", "مرحبا 🌱", "a.b", " name with spaces ", String(repeating: "a", count: 255)] {
    check(RepositoryItemName.validationMessage(name) == nil, "Must accept valid Unicode name")
}
check(RepositoryItemName.validationMessage(NoteFileName.finalName(for: String(repeating: "a", count: 253))) != nil, "Appended extension must respect byte limit")
for value in ["a\0b", "a\tb", "a\nb", "a\u{7f}b"] {
    check(GitIdentityValidation.nameIssue(value) != nil, "Reject identity control")
    check(GitIdentityValidation.emailIssue(value) != nil, "Reject email control")
}
check(GitIdentityValidation.nameIssue("مرحبا 👩‍💻") == nil, "Accept Unicode identity")
check(GitIdentityValidation.emailIssue("you@localhost") == nil, "Accept dotless Git email")
check(GitIdentityValidation.emailIssue("you@host..invalid") != nil, "Reject malformed domain")
check(TideTextDiff.lines("one\r\ntwo\n") == ["one", "two"], "CRLF line splitting")
check(TideTextDiff.lines("").isEmpty, "Empty diff")
let diff = TideTextDiff.wordRuns(old: "hello world", new: "hello 🌱")
check(diff.old.map { $0.0 }.joined() == "hello world", "Old diff roundtrip")
check(diff.new.map { $0.0 }.joined() == "hello 🌱", "Unicode diff roundtrip")
check(diff.new.contains { $0.1 && $0.0.contains("🌱") }, "Highlight Unicode addition")
print("PASS: \(checks) portable Apple source assertions (filename, identity, Tide text diff)")
