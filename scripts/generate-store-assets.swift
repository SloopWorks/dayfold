#!/usr/bin/env swift

import AppKit
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

private struct RGB {
  let r: CGFloat
  let g: CGFloat
  let b: CGFloat

  init(_ hex: UInt32) {
    r = CGFloat((hex >> 16) & 0xff) / 255
    g = CGFloat((hex >> 8) & 0xff) / 255
    b = CGFloat(hex & 0xff) / 255
  }

  var cgColor: CGColor { CGColor(red: r, green: g, blue: b, alpha: 1) }
}

private let workspace = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
private let iosOutput = workspace.appendingPathComponent("apps/iosApp/Resources/Assets.xcassets/AppIcon.appiconset/dayfold-1024.png")
private let playOutput = workspace.appendingPathComponent("designs/store/play-icon-512.png")
private let playMetadataIcon = workspace.appendingPathComponent("store/android/metadata/android/en-US/images/icon.png")
private let featureOutput = workspace.appendingPathComponent("store/android/metadata/android/en-US/images/featureGraphic.png")

private func writePng(_ image: CGImage, to output: URL) throws {
  try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
  guard let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil)
  else { throw NSError(domain: "DayfoldAssets", code: 2) }
  CGImageDestinationAddImage(destination, image, nil)
  guard CGImageDestinationFinalize(destination) else { throw NSError(domain: "DayfoldAssets", code: 3) }
}

private func bitmapContext(width: Int, height: Int) throws -> CGContext {
  let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
  guard let context = CGContext(
    data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
    space: colorSpace, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
  ) else { throw NSError(domain: "DayfoldAssets", code: 1) }
  return context
}

private func makeIcon(size: Int, output: URL) throws {
  try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
  let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
  let context = try bitmapContext(width: size, height: size)

  context.translateBy(x: 0, y: CGFloat(size))
  context.scaleBy(x: 1, y: -1)
  let scale = CGFloat(size) / 1024
  context.scaleBy(x: scale, y: scale)

  let cardGradient = CGGradient(
    colorsSpace: colorSpace,
    colors: [RGB(0xFF8A6E).cgColor, RGB(0xC0381E).cgColor] as CFArray,
    locations: [0, 1]
  )!
  context.drawLinearGradient(
    cardGradient,
    start: CGPoint(x: 80.5, y: -60.6),
    end: CGPoint(x: 943.5, y: 1084.6),
    options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
  )

  let flap = CGMutablePath()
  flap.move(to: CGPoint(x: 552.96, y: 0))
  flap.addLine(to: CGPoint(x: 1024, y: 0))
  flap.addLine(to: CGPoint(x: 1024, y: 471.04))
  flap.closeSubpath()
  context.saveGState()
  context.addPath(flap)
  context.clip()
  let flapGradient = CGGradient(
    colorsSpace: colorSpace,
    colors: [RGB(0xFFE2D8).cgColor, RGB(0xFFB4A3).cgColor] as CFArray,
    locations: [0, 1]
  )!
  context.drawLinearGradient(
    flapGradient,
    start: CGPoint(x: 1024, y: 0),
    end: CGPoint(x: 552.96, y: 471.04),
    options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
  )
  context.restoreGState()

  let underside = CGMutablePath()
  underside.move(to: CGPoint(x: 552.96, y: 0))
  underside.addLine(to: CGPoint(x: 1024, y: 471.04))
  underside.addLine(to: CGPoint(x: 552.96, y: 471.04))
  underside.closeSubpath()
  context.addPath(underside)
  context.setFillColor(CGColor(red: 90 / 255, green: 17 / 255, blue: 0, alpha: 0.20))
  context.fillPath()

  guard let image = context.makeImage() else { throw NSError(domain: "DayfoldAssets", code: 2) }
  try writePng(image, to: output)
}

private func loadImage(_ url: URL) throws -> CGImage {
  guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
  else { throw NSError(domain: "DayfoldAssets", code: 4) }
  return image
}

private func makeFeatureGraphic() throws {
  let context = try bitmapContext(width: 1024, height: 500)
  let space = CGColorSpace(name: CGColorSpace.sRGB)!
  let background = CGGradient(
    colorsSpace: space,
    colors: [RGB(0xFFF8F6).cgColor, RGB(0xFFDAD2).cgColor] as CFArray,
    locations: [0, 1]
  )!
  context.drawLinearGradient(background, start: CGPoint(x: 0, y: 0), end: CGPoint(x: 1024, y: 500), options: [])
  let icon = try loadImage(playOutput)
  let mark = CGRect(x: 377, y: 115, width: 270, height: 270)
  context.saveGState()
  context.setShadow(offset: CGSize(width: 0, height: -10), blur: 28, color: CGColor(red: 0.35, green: 0.07, blue: 0, alpha: 0.20))
  context.addPath(CGPath(roundedRect: mark, cornerWidth: 81, cornerHeight: 81, transform: nil))
  context.clip()
  context.draw(icon, in: mark)
  context.restoreGState()
  guard let image = context.makeImage() else { throw NSError(domain: "DayfoldAssets", code: 5) }
  try writePng(image, to: featureOutput)
}

private func makeScreenshot(source: URL, width: Int, height: Int, output: URL, aspectFit: Bool) throws {
  let image = try loadImage(source)
  let context = try bitmapContext(width: width, height: height)
  context.setFillColor(RGB(0xFFF8F6).cgColor)
  context.fill(CGRect(x: 0, y: 0, width: width, height: height))
  let sourceRatio = CGFloat(image.width) / CGFloat(image.height)
  let targetRatio = CGFloat(width) / CGFloat(height)
  let rect: CGRect
  if aspectFit && sourceRatio > targetRatio {
    let scaledHeight = CGFloat(width) / sourceRatio
    rect = CGRect(x: 0, y: (CGFloat(height) - scaledHeight) / 2, width: CGFloat(width), height: scaledHeight)
  } else if aspectFit {
    let scaledWidth = CGFloat(height) * sourceRatio
    rect = CGRect(x: (CGFloat(width) - scaledWidth) / 2, y: 0, width: scaledWidth, height: CGFloat(height))
  } else {
    rect = CGRect(x: 0, y: 0, width: width, height: height)
  }
  context.interpolationQuality = .high
  context.draw(image, in: rect)
  guard let rendered = context.makeImage() else { throw NSError(domain: "DayfoldAssets", code: 6) }
  try writePng(rendered, to: output)
}

try makeIcon(size: 1024, output: iosOutput)
for size in [20, 29, 40, 58, 60, 76, 80, 87, 120, 152, 167, 180] {
  try makeIcon(
    size: size,
    output: iosOutput.deletingLastPathComponent().appendingPathComponent("dayfold-\(size).png")
  )
}
try makeIcon(size: 512, output: playOutput)
try makeIcon(size: 512, output: playMetadataIcon)
try makeFeatureGraphic()

let screenshotSources = [
  ("01-sign-in", "auth-signin"),
  ("02-family-briefing", "feed-enriched"),
  ("03-event-hub", "hub-detail-enriched"),
]
for (outputName, sourceName) in screenshotSources {
  let source = workspace.appendingPathComponent("apps/ui/src/desktopTest/resources/snapshots/macos/\(sourceName).png")
  try makeScreenshot(
    source: source, width: 1290, height: 2796,
    output: workspace.appendingPathComponent("store/apple/screenshots/6.9-inch/\(outputName).png"), aspectFit: false
  )
  try makeScreenshot(
    source: source, width: 1080, height: 1920,
    output: workspace.appendingPathComponent("store/android/metadata/android/en-US/images/phoneScreenshots/\(outputName).png"), aspectFit: true
  )
}

// iPad captures come from the snapshot batch rather than this renderer. Re-encode them
// over the opaque store background so App Store uploads never depend on alpha handling.
for (outputName, _) in screenshotSources {
  let ipad = workspace.appendingPathComponent("store/apple/screenshots/13-inch/\(outputName).png")
  if FileManager.default.fileExists(atPath: ipad.path) {
    try makeScreenshot(source: ipad, width: 2064, height: 2752, output: ipad, aspectFit: false)
    try makeScreenshot(
      source: ipad, width: 2064, height: 2752,
      output: workspace.appendingPathComponent("store/apple/fastlane-screenshots/en-US/ipad-\(outputName).png"), aspectFit: false
    )
  }
  let iphone = workspace.appendingPathComponent("store/apple/screenshots/6.9-inch/\(outputName).png")
  try makeScreenshot(
    source: iphone, width: 1290, height: 2796,
    output: workspace.appendingPathComponent("store/apple/fastlane-screenshots/en-US/iphone-\(outputName).png"), aspectFit: false
  )
}
