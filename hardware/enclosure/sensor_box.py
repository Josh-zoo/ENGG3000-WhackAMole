"""
ENGG3000 Whack-a-Mole sensor enclosures (Bambu P1S, 0.2 mm, gyroid 15-25%).

Two boxes, two RCWL-1601 sensors each, angles built into the front faces.
Back wall = reference edge: set it parallel to the screen line and the
sensors point at the right angles automatically.

  LEFT box  (S1 outer, S2 inner):  S1 15 deg in, S2 40 deg in
  RIGHT box (S3 inner, S4 outer):  mirror image, S3 40 deg in, S4 15 deg in

Envelope must stay inside 100 x 100 x 50 mm (brief).
All dimensions in mm. Edit the PARAMETERS block and re-run.
"""
import math, sys
import numpy as np
from manifold3d import Manifold, CrossSection, JoinType
import trimesh

# ---------------- PARAMETERS ----------------
ANG_OUTER = 15.0      # S1 / S4 toe-in (deg)
ANG_INNER = 40.0      # S2 / S3 toe-in (deg)
L_OUTER   = 56.0      # outer facet length (outer skin)
L_INNER   = 54.0      # inner facet length (outer skin)
DEPTH     = 98.0      # back wall to front-most corner
H_BASE    = 47.0      # base height incl. floor
T_LID     = 2.0       # lid plate (total height = H_BASE + T_LID)
WALL      = 2.0
FLOOR     = 2.0

# RCWL-1601 (measure yours with calipers and adjust!)
# measured by Josh 17 Oct: pitch 23 +/-1, cans 17 +/-1, board 41 x 17, cans stick out 10
PCB_W     = 41.0      # board width
PCB_H     = 17.0      # board height
PCB_T     = 1.6
XDCR_D    = 17.0      # transducer can diameter
XDCR_LEN  = 10.0      # how far the cans stick out from the board front
XDCR_PITCH= 23.0      # centre-to-centre of the two cans
HOLE_D    = 17.8      # clearance hole (board is held by the rib detents, not the cans)
HOLE_SLOP = 1.2       # extra hole length along the face (covers the +/-1 mm pitch spread)
PCB_LIFT  = 2.0       # board sits on a 2 mm ledge so the can holes clear the floor
SENSOR_Z  = FLOOR + PCB_LIFT + PCB_H / 2   # can centres; pins point UP
GUIDE_GAP = PCB_W + 0.4  # gap between the side ribs
DETENT    = 0.4       # bump on each rib that the board snaps past

# inside layout (left box; right box is mirrored)
BATT = (34.0, 58.0, 34.0)   # 3xAA holder x, y, z (lies with its 58 mm side front-to-back)
ESP  = (30.0, 50.0)         # ESP32 x, y, USB-C end against the back wall

# lid friction fit
LID_GAP   = 0.15      # clearance per side between lid plug and wall
LID_PLUG  = 4.0       # plug depth
LID_RING  = 1.6       # plug ring thickness

# back-wall openings (check against your parts)
USB_W, USB_H, USB_Z = 13.0, 12.0, 6.0      # USB-C opening; x is centred on the ESP pocket
SW_W,  SW_H,  SW_Z,  SW_X  = 13.2, 8.2, 30.0, 82.0   # 13 x 8 switch hole (+0.2 print shrink)
# --------------------------------------------

d2r = math.radians

def outline():
    """Outer footprint, LEFT box. Back wall on y=0, field centre toward +x."""
    a1, a2 = d2r(ANG_OUTER), d2r(ANG_INNER)
    xj = L_OUTER * math.cos(a1)
    yj = DEPTH - L_OUTER * math.sin(a1)
    W  = xj + L_INNER * math.cos(a2)
    yR = yj - L_INNER * math.sin(a2)
    pts = [(0, 0), (W, 0), (W, yR), (xj, yj), (0, DEPTH)]
    facets = [  # (start point, angle, length, name)
        ((0.0, DEPTH), ANG_OUTER, L_OUTER, "outer"),
        ((xj, yj),     ANG_INNER, L_INNER, "inner"),
    ]
    return pts, facets, W

def frame(start, ang_deg):
    a = d2r(ang_deg)
    t = np.array([math.cos(a), -math.sin(a)])   # along the face, left->right
    n = np.array([math.sin(a),  math.cos(a)])   # outward normal (beam direction)
    return np.array(start, float), t, n

def place(m, pos2, ang_deg, z):
    """m is built with x = along face, y = outward normal. Rotate into place."""
    return m.rotate([0, 0, -ang_deg]).translate([pos2[0], pos2[1], z])

def build_base(pts, facets):
    cs_out = CrossSection([pts])
    cs_in  = cs_out.offset(-WALL, JoinType.Miter)
    shell  = cs_out.extrude(H_BASE) - cs_in.extrude(H_BASE).translate([0, 0, FLOOR])

    adds, cuts, info = [], [], []
    for start, ang, L, name in facets:
        p0, t, n = frame(start, ang)
        s = L / 2.0                              # sensor centred on its facet
        c = p0 + t * s
        # transducer holes (obround: slop along the face)
        r = HOLE_D / 2
        hole2d = CrossSection.circle(r, 64)
        hole2d = (hole2d.translate([-HOLE_SLOP/2, 0]) + hole2d.translate([HOLE_SLOP/2, 0])).hull()
        hole = hole2d.extrude(WALL * 4).translate([0, 0, -WALL * 2])  # axis z
        hole = hole.rotate([-90, 0, 0])                               # axis -> +y
        for k in (-1, 1):
            hc = c + t * (k * XDCR_PITCH / 2)
            cuts.append(place(hole, hc, ang, SENSOR_Z))
        # board guides on the inside of the face
        inner_face = c - n * WALL                 # point on inner skin
        gap = GUIDE_GAP
        z_pcb_bot = SENSOR_Z - PCB_H / 2
        for k in (-1, 1):
            rib = Manifold.cube([1.6, 7.0, 22.0]).translate([-0.8, -7.0, 0])
            # snap bump just behind the board's back face
            bump = Manifold.cylinder(PCB_H - 4, 0.6, 0.6, 24).translate(
                [-k * (0.8 + 0.6 - DETENT), -(PCB_T + 0.15 + 0.6), z_pcb_bot + 2 - FLOOR])
            off = k * (gap / 2 + 0.8)
            adds.append(place(rib + bump, inner_face + t * off, ang, FLOOR))
        # ledge the board bottom rests on
        if z_pcb_bot > FLOOR:
            ledge = Manifold.cube([PCB_W - 6, PCB_T + 1.0, z_pcb_bot - FLOOR]).translate(
                [-(PCB_W - 6) / 2, -(PCB_T + 1.0), 0])
            adds.append(place(ledge, inner_face, ang, FLOOR))
        info.append((name, ang, c, n))

    base = shell
    for a in adds:
        base = base + (a ^ cs_in.extrude(H_BASE))   # keep guides inside cavity
    for c_ in cuts:
        base = base - c_

    # locating ribs for the battery holder and ESP32
    bx, by, _ = BATT; ex, ey = ESP
    x0 = WALL; y0 = WALL; c = 0.6; rt = 1.6
    ribs = [
        ((x0 + bx + c, y0), (rt, by + c + rt), 8.0),               # battery right side
        ((x0, y0 + by + c), (bx + c + rt, rt), 8.0),               # battery front
        ((x0 + bx + c + rt + ex + c, y0), (rt, ey - 8), 6.0),      # ESP right side
        ((x0 + bx + c + rt, y0 + ey + c), (ex + c + rt, rt), 6.0), # ESP front
    ]
    for (px, py), (sx, sy), h in ribs:
        base = base + Manifold.cube([sx, sy, h]).translate([px, py, FLOOR])
    esp_cx = x0 + bx + c + rt + (ex + c) / 2

    # back-wall openings
    W = max(p[0] for p in pts)
    def back_slot(w, h, z, x):
        x = W / 2 if x is None else x
        return Manifold.cube([w, WALL * 4, h]).translate([x - w / 2, -WALL * 2, z])
    base = base - back_slot(USB_W, USB_H, USB_Z, esp_cx)
    base = base - back_slot(SW_W, SW_H, SW_Z, SW_X)
    # finger notch to pop the lid
    base = base - Manifold.cube([12, WALL * 4, 2.5]).translate([W / 2 - 6, -WALL * 2, H_BASE - 2.5])
    return base, info

def build_lid(pts, facets):
    """Exported plate-down (plate on the bed, plug pointing up). Flip it onto the base."""
    cs_out = CrossSection([pts])
    plate  = cs_out.extrude(T_LID)
    plug_o = cs_out.offset(-(WALL + LID_GAP), JoinType.Miter)
    plug_i = plug_o.offset(-LID_RING, JoinType.Miter)
    ring   = (plug_o - plug_i).extrude(LID_PLUG).translate([0, 0, T_LID])
    # notch the plug above each sensor header so the jumper leads can bend
    for start, ang, L, _ in facets:
        p0, t, n = frame(start, ang)
        c = p0 + t * (L / 2)
        notch = Manifold.cube([18, 10, LID_PLUG + 1]).translate([-9, -10, T_LID])
        ring = ring - place(notch, c, ang, 0)
    return plate + ring

def mirror_x(m, W):
    return m.mirror([1, 0, 0]).translate([W, 0, 0])

def to_trimesh(m):
    mesh = m.to_mesh()
    return trimesh.Trimesh(vertices=np.array(mesh.vert_properties)[:, :3],
                           faces=np.array(mesh.tri_verts), process=False)

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    pts, facets, W = outline()
    base, info = build_base(pts, facets)
    lid = build_lid(pts, facets)

    parts = {
        "LEFT_S1-S2_base": base,
        "LEFT_S1-S2_lid":  lid,
        "RIGHT_S3-S4_base": mirror_x(base, W),
        "RIGHT_S3-S4_lid":  mirror_x(lid, W),
    }
    for name, m in parts.items():
        tm = to_trimesh(m)
        assert tm.is_watertight, name
        tm.export(f"{out}/{name}.stl")
        ext = tm.bounds[1] - tm.bounds[0]
        print(f"{name:18s} {ext[0]:6.1f} x {ext[1]:6.1f} x {ext[2]:5.1f} mm  vol {tm.volume/1000:5.1f} cm3")

    # verify facet beam directions from the actual outline
    print("\nBeam directions (deg from straight ahead, + = toward field centre):")
    for i, (p, q) in enumerate([(pts[4], pts[3]), (pts[3], pts[2])]):
        dx, dy = q[0] - p[0], q[1] - p[1]
        nrm = (-dy, dx) if False else (dy * -1 * -1, -dx)  # outward normal = (-dy, dx) rotated
        nx, ny = -dy, dx
        if ny < 0: nx, ny = -nx, -ny
        print(f"  LEFT  facet {i+1}: {math.degrees(math.atan2(nx, ny)):5.1f}")
    print(f"\nFootprint {W:.1f} x {DEPTH:.1f}, height {H_BASE + T_LID:.1f}")
