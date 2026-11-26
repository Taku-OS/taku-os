// Offline compute experiment only. No camera capture, network or robot transport.
// Input: a local image. Output: JSON measurements and anonymous person boxes.
import Foundation
import Vision
import ImageIO

guard CommandLine.arguments.count == 2 else {
    fputs("Usage: swift benchmark-human-detection.swift /absolute/local/image.jpg\n", stderr)
    exit(2)
}
let url = URL(fileURLWithPath: CommandLine.arguments[1])
guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil),
      cgImage.width > 64, cgImage.height > 64 else {
    fputs("Cannot decode input image\n", stderr)
    exit(1)
}

var firstRequestMs = 0.0
var times: [Double] = []
var lastBoxes: [[String: Any]] = []
var counts: [Int] = []
var frames: [[String: Any]] = []
var revision = 0
for index in 0..<33 {
    try autoreleasepool {
        let request = VNDetectHumanRectanglesRequest()
        request.upperBodyOnly = false
        revision = request.revision
        // Vary the crop to avoid benchmarking only an identical cached image.
        // These are still synthetic variants, not a representative video dataset.
        let crop = cgImage.cropping(to: CGRect(x: index, y: index,
                                              width: cgImage.width - 32, height: cgImage.height - 32))!
        let handler = VNImageRequestHandler(cgImage: crop, orientation: .up)
        let start = DispatchTime.now().uptimeNanoseconds
        try handler.perform([request])
        let ms = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
        if index == 0 { firstRequestMs = ms }
        if index >= 3 { times.append(ms) }
        let observations = (request.results ?? []).filter { $0.confidence >= 0.5 }
        if index >= 3 { counts.append(observations.count) }
        if index >= 3 {
            let best = observations.max { $0.confidence < $1.confidence }
            frames.append(["cropOffset": index, "count": observations.count,
                           "highestConfidenceCenterXInOriginalPixels": best.map {
                               Double(index) + $0.boundingBox.midX * Double(cgImage.width - 32)
                           } ?? NSNull(),
                           "highestConfidence": best.map { Double($0.confidence) } ?? NSNull()])
        }
        lastBoxes = observations.map { item in
            let box = item.boundingBox
            return ["confidence": Double(item.confidence),
                    "bboxNormalizedBottomLeft": [box.minX, box.minY, box.width, box.height]]
        }
    }
}
let sorted = times.sorted()
let median = (sorted[14] + sorted[15]) / 2
let p95 = sorted[Int(ceil(Double(sorted.count) * 0.95)) - 1]
let result: [String: Any] = [
    "experiment": "offline-static-image-human-detection",
    "engine": "Apple Vision VNDetectHumanRectanglesRequest",
    "revision": revision,
    "os": ProcessInfo.processInfo.operatingSystemVersionString,
    "inputWidth": cgImage.width, "inputHeight": cgImage.height,
    "processedWidth": cgImage.width - 32, "processedHeight": cgImage.height - 32,
    "inputVariation": "33 distinct crops offset by 0..32 pixels on each axis",
    "warmupIterations": 3, "measuredIterations": times.count,
    "firstRequestMs": firstRequestMs, "medianMs": median, "p95Ms": p95,
    "maxMs": sorted.last!, "minMs": sorted.first!,
    "detectionCounts": counts, "boxes": lastBoxes,
    "frames": frames,
    "robotConnected": false, "movementSent": false,
    "limits": ["Synthetic crops of one public still image; not robot video",
               "First request in this process; system/model may already be warm",
               "No video transport/decode, tracking, depth, avoidance or control latency measured",
               "No person identity recognition or target persistence measured"]
]
let json = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
print(String(data: json, encoding: .utf8)!)
