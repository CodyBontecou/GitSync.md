#!/usr/bin/env swift

import AppKit
import Foundation
import Vision

struct Finding: Codable {
    let path: String
    let tokens: [String]
    let excerpt: String
}

let arguments = CommandLine.arguments
guard arguments.count == 3 else {
    fputs("usage: ocr_forbidden_tokens.swift QUEUE_JSON OUTPUT_JSON\n", stderr)
    exit(2)
}

let queueURL = URL(fileURLWithPath: arguments[1])
let outputURL = URL(fileURLWithPath: arguments[2])
let data = try Data(contentsOf: queueURL)
let object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
let assets = object["assets"] as! [[String: Any]]
let forbidden = [
    "claude", "cody", "codybontecou", "isolated.tech", "karpathy",
    "andrej", "llm-wiki", "ilm-wiki", "1lm-wiki", "health-md",
    "example@isolated", "a14ab110", "414b110"
]

var findings: [Finding] = []
for (index, asset) in assets.enumerated() {
    autoreleasepool {
        guard let path = asset["output"] as? String,
              let image = NSImage(contentsOfFile: path),
              let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
        else { return }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        request.minimumTextHeight = 0.008
        let handler = VNImageRequestHandler(cgImage: cgImage)
        try? handler.perform([request])
        let text = (request.results ?? [])
            .compactMap { $0.topCandidates(1).first?.string }
            .joined(separator: "\n")
        let lowered = text.lowercased()
        let matches = forbidden.filter { lowered.contains($0) }
        if !matches.isEmpty {
            findings.append(Finding(path: path, tokens: matches, excerpt: String(text.prefix(1200))))
        }
        if (index + 1) % 25 == 0 {
            print("scanned \(index + 1)/\(assets.count)", terminator: "\n")
            fflush(stdout)
        }
    }
}

let encoder = JSONEncoder()
encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
try encoder.encode(findings).write(to: outputURL)
print("findings \(findings.count)")
