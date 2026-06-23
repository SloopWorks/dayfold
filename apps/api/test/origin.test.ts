import { describe, it, expect } from "vitest";
import { classifyOrigin } from "../src/auth/origin.ts";

describe("classifyOrigin (no-vendor datacenter heuristic)", () => {
  it("flags bundled cloud ranges as datacenter", () => {
    expect(classifyOrigin("52.1.2.3")).toBe("datacenter");    // AWS 52.0.0.0/11
    expect(classifyOrigin("159.65.10.20")).toBe("datacenter"); // DigitalOcean
    expect(classifyOrigin("34.120.0.1")).toBe("datacenter");   // GCP 34.0.0.0/9
    expect(classifyOrigin("104.16.5.5")).toBe("datacenter");   // Cloudflare
  });
  it("public IPs outside the list → residential", () => {
    expect(classifyOrigin("24.1.2.3")).toBe("residential");
  });
  it("private / reserved / loopback / missing / ipv6 → unknown", () => {
    expect(classifyOrigin("10.0.0.1")).toBe("unknown");
    expect(classifyOrigin("192.168.1.5")).toBe("unknown");
    expect(classifyOrigin("127.0.0.1")).toBe("unknown");
    expect(classifyOrigin("203.0.113.7")).toBe("unknown"); // TEST-NET-3
    expect(classifyOrigin("unknown")).toBe("unknown");
    expect(classifyOrigin(null)).toBe("unknown");
    expect(classifyOrigin("2001:db8::1")).toBe("unknown");
    expect(classifyOrigin("not-an-ip")).toBe("unknown");
  });
});
