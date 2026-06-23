// No-vendor origin classifier for the device-grant anti-phishing warning
// (ADR 0011 §7 intent, ADR 0029 review). Classifies an IP as `datacenter` from a
// bundled list of major cloud/hosting CIDRs — no geoip vendor, no recurring cost.
// This is a HEURISTIC, not authority: a datacenter origin only drives a "verify
// this is you" warning; the human confirming the user_code is the real gate.
//
// Coverage is a representative subset of large cloud allocations. It can be
// expanded at build time from providers' published ranges (AWS ip-ranges.json,
// GCP cloud.json, Azure ServiceTags) without changing this interface.

export type OriginKind = "datacenter" | "residential" | "unknown";

// Curated CIDRs that are predominantly cloud/hosting (datacenter) space.
const DATACENTER_CIDRS: string[] = [
  // AWS
  "3.0.0.0/9", "13.32.0.0/12", "15.177.0.0/16", "18.32.0.0/11", "35.152.0.0/13",
  "52.0.0.0/11", "54.144.0.0/12", "99.78.0.0/18",
  // Google Cloud
  "34.0.0.0/9", "35.184.0.0/13", "104.196.0.0/14", "130.211.0.0/16", "146.148.0.0/17",
  // Microsoft Azure
  "20.0.0.0/8", "40.64.0.0/10", "13.64.0.0/11", "104.40.0.0/13", "52.224.0.0/11",
  // DigitalOcean
  "159.65.0.0/16", "165.227.0.0/16", "167.71.0.0/16", "134.209.0.0/16",
  "138.197.0.0/16", "157.230.0.0/16", "64.225.0.0/16", "146.190.0.0/16",
  // Linode / Akamai
  "45.33.0.0/16", "45.56.0.0/16", "139.162.0.0/16", "172.104.0.0/15", "173.255.192.0/18",
  // Hetzner
  "5.9.0.0/16", "88.99.0.0/16", "116.202.0.0/16", "95.216.0.0/15", "78.46.0.0/15",
  // OVH
  "51.38.0.0/16", "51.68.0.0/16", "137.74.0.0/16", "145.239.0.0/16", "51.83.0.0/16",
  // Oracle Cloud
  "129.146.0.0/16", "132.145.0.0/16", "140.238.0.0/16",
  // Cloudflare
  "104.16.0.0/13", "172.64.0.0/13", "162.158.0.0/15",
  // Vultr
  "45.32.0.0/16", "45.63.0.0/16", "108.61.0.0/16", "149.28.0.0/16",
];

// Precompute [base, mask] uint32 pairs once.
const RANGES: Array<[number, number]> = DATACENTER_CIDRS.map((cidr) => {
  const [addr, bitsStr] = cidr.split("/");
  const bits = Number(bitsStr);
  const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
  return [(ipv4ToInt(addr)! & mask) >>> 0, mask];
});

function ipv4ToInt(ip: string): number | null {
  const m = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return null;
  const o = m.slice(1, 5).map(Number);
  if (o.some((n) => n > 255)) return null;
  return ((o[0] << 24) | (o[1] << 16) | (o[2] << 8) | o[3]) >>> 0;
}

// Private/reserved/loopback/link-local → not a meaningful public signal.
function isPrivateOrReserved(n: number): boolean {
  const inR = (cidr: string) => {
    const [addr, b] = cidr.split("/");
    const bits = Number(b);
    const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
    return ((n & mask) >>> 0) === ((ipv4ToInt(addr)! & mask) >>> 0);
  };
  return ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
    "169.254.0.0/16", "100.64.0.0/10", "0.0.0.0/8", "192.0.2.0/24",
    "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4"].some(inR);
}

export function classifyOrigin(ip: string | null | undefined): OriginKind {
  if (!ip || ip === "unknown") return "unknown";
  // IPv6 not covered by the bundled list yet → unknown (no false "residential").
  if (ip.includes(":")) return "unknown";
  const n = ipv4ToInt(ip);
  if (n === null) return "unknown";
  if (isPrivateOrReserved(n)) return "unknown";
  for (const [base, mask] of RANGES) if (((n & mask) >>> 0) === base) return "datacenter";
  return "residential"; // public IPv4 with no datacenter signal
}
