import { describe, it, expect } from "vitest";
import { app } from "../src/app.ts";

// AUTH-S6-D Phase 2 — deep-link association files (App Links / Universal Links)
// + the /device browser landing. No auth; served on the API origin.
describe("deep-link association files + /device landing", () => {
  it("assetlinks.json delegates URL handling to the android app", async () => {
    const res = await app.request("/.well-known/assetlinks.json");
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body[0].relation).toContain("delegate_permission/common.handle_all_urls");
    expect(body[0].target.namespace).toBe("android_app");
    expect(body[0].target.package_name).toBe("com.sloopworks.dayfold");
    expect(body[0].target.sha256_cert_fingerprints.length).toBeGreaterThan(0);
  });

  it("ANDROID_CERT_SHA256 env overrides the fingerprints (release + debug)", async () => {
    const prev = process.env.ANDROID_CERT_SHA256;
    process.env.ANDROID_CERT_SHA256 = "AA:BB, CC:DD";
    const body = await (await app.request("/.well-known/assetlinks.json")).json();
    expect(body[0].target.sha256_cert_fingerprints).toEqual(["AA:BB", "CC:DD"]);
    if (prev === undefined) delete process.env.ANDROID_CERT_SHA256;
    else process.env.ANDROID_CERT_SHA256 = prev;
  });

  it("AASA is application/json (no extension) with the /device paths", async () => {
    const res = await app.request("/.well-known/apple-app-site-association");
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("application/json");
    const body = await res.json();
    expect(body.applinks.details[0].paths).toContain("/device");
  });

  it("/device renders the landing with the (sanitized) user_code", async () => {
    const res = await app.request("/device?user_code=WDJF-7K2P");
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/html");
    const html = await res.text();
    expect(html).toContain("WDJF-7K2P");
    expect(html).toContain("Approve this device");
  });

  it("/device strips angle brackets from the code (no tag injection)", async () => {
    const html = await (await app.request("/device?user_code=%3Cscript%3Ex")).text();
    expect(html).not.toContain("<script>");
  });
});
