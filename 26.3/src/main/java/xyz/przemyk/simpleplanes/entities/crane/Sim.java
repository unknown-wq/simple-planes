package xyz.przemyk.simpleplanes.entities.crane;

import java.util.Locale;

/**
 * Reproducible check of the crane's plain physics (acceptance C1). No Minecraft imports:
 * {@code javac -d out entities/crane/*.java && java -cp out xyz.przemyk.simpleplanes.entities.crane.Sim}
 */
public final class Sim {

    static final double COW = 1.13;

    final MultirotorPhysics drone = new MultirotorPhysics();
    final CraneController ctl = new CraneController();
    final SlungLoad rope = new SlungLoad();
    final double[] extra = new double[3];

    Sim(double load, double length) {
        rope.mass = load;
        rope.length = rope.targetLength = length;
        drone.mass = 1.0 + load;
        drone.thrust = drone.mass * MultirotorPhysics.G;
    }

    void tick(double tx, double ty, double tz) {
        ctl.control(drone, rope, tx, ty, tz);
        rope.reaction(drone, extra);
        drone.step(extra);
        rope.swing(drone);
    }

    public static void main(String[] args) {
        boolean ok = true;
        System.out.println("== hover thrust");
        for (double m : new double[]{0, COW, SlungLoad.MAX_LOAD}) {
            Sim s = new Sim(m, 3);
            for (int t = 0; t < 400; t++) {
                s.tick(0, 0, 0);
            }
            System.out.printf(Locale.ROOT, "  load %.2f: T %.4f  pos err %.4f%n", m, s.drone.thrust, Math.abs(s.drone.p[1]));
        }

        System.out.println("== climb (target +200), vy at t=300");
        for (double m : new double[]{0, 0.65, COW, 1.50}) {
            Sim s = new Sim(m, 3);
            for (int t = 0; t < 300; t++) {
                s.tick(0, 200, 0);
            }
            System.out.printf(Locale.ROOT, "  load %.2f: vy %.3f sat %b%n", m, s.drone.v[1], s.ctl.saturated);
        }

        System.out.println("== 20 b horizontal step");
        Step empty = step(0, 6, 0);
        System.out.println("  empty:      " + empty);
        Step cow = step(COW, 6, 0);
        System.out.println("  cow, L=6:   " + cow);
        ok &= empty.settle >= 0 && empty.settle <= 110;
        ok &= cow.settle >= 0 && cow.settle <= 150 && cow.peakSwing <= 32 && cow.residual <= 3;
        System.out.println("  cow, L=3:   " + step(COW, 3, 0));
        System.out.println("  0.70, L=3:  " + step(0.70, 3, 0));
        System.out.println("  0.11, L=3:  " + step(0.11, 3, 0));
        System.out.println("  1.50, L=6:  " + step(1.50, 6, 0));
        System.out.println("  diagonal 20,20 empty: " + step(0, 6, 20));

        System.out.println("== swing-damping sign: free swing 20 deg, cow, L=6 (amplitude over one period from t)");
        for (double k : new double[]{-0.4, 0.0, 0.4}) {
            Sim s = new Sim(COW, 6);
            s.ctl.swingGain = k;
            s.rope.thetaX = Math.toRadians(20);
            double[] amp = new double[4];
            for (int t = 0; t < 480; t++) {
                s.tick(0, 0, 0);
                for (int w = 0; w < 4; w++) {
                    int from = 100 * (w + 1);
                    if (t >= from && t < from + 77) {
                        amp[w] = Math.max(amp[w], s.rope.swingDegrees());
                    }
                }
            }
            System.out.printf(Locale.ROOT, "  k=%+.1f: 5 s %.2f  10 s %.2f  15 s %.2f  20 s %.2f deg; drone drift %.2f b%n",
                k, amp[0], amp[1], amp[2], amp[3], Math.abs(s.drone.p[0]));
        }

        System.out.println("== 20 b vertical step");
        for (double m : new double[]{0, COW}) {
            Sim s = new Sim(m, 3);
            int settle = -1;
            double over = 0, peak = 0;
            for (int t = 1; t <= 600; t++) {
                s.tick(0, 20, 0);
                over = Math.max(over, s.drone.p[1] - 20);
                peak = Math.max(peak, s.drone.v[1]);
                if (Math.abs(s.drone.p[1] - 20) > 0.5) {
                    settle = -1;
                } else if (settle < 0) {
                    settle = t;
                }
            }
            System.out.printf(Locale.ROOT, "  load %.2f: settle %d t, overshoot %.2f, peak vy %.3f%n", m, settle, over, peak);
            if (m == 0) {
                ok &= settle >= 0 && settle <= 160 && over <= 0.5 && peak >= 0.22 && peak <= 0.32;
            }
        }

        System.out.println("== overload 2.2, target +30");
        {
            Sim s = new Sim(2.2, 3);
            int sat = 0;
            for (int t = 0; t < 300; t++) {
                s.tick(0, 30, 0);
                if (s.ctl.saturated) {
                    sat++;
                }
            }
            System.out.printf(Locale.ROOT, "  after 300 t: y %.1f vy %.3f saturated %d t%n", s.drone.p[1], s.drone.v[1], sat);
        }

        System.out.println("== pendulum period, L=6, pivot fixed");
        {
            SlungLoad r = new SlungLoad();
            r.length = 6;
            r.mass = COW;
            r.thetaX = Math.toRadians(2);
            MultirotorPhysics fixed = new MultirotorPhysics();
            int last = -1, first = -1, crossings = 0;
            double prev = r.thetaX;
            for (int t = 1; t < 1000; t++) {
                r.swing(fixed);
                if (prev > 0 && r.thetaX <= 0) {
                    if (first < 0) {
                        first = t;
                    } else {
                        last = t;
                        crossings++;
                    }
                }
                prev = r.thetaX;
            }
            System.out.printf(Locale.ROOT, "  period %.1f t (2 pi sqrt(L/G) = %.1f)%n",
                (last - first) / (double) crossings, 2 * Math.PI * Math.sqrt(6 / MultirotorPhysics.G));
        }

        System.out.println("== spec split reaction model (for comparison)");
        SlungLoad.splitModel = true;
        System.out.println("  cow, L=6 step: " + step(COW, 6, 0));
        SlungLoad.splitModel = false;

        System.out.println(ok ? "C1 PASS" : "C1 FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    record Step(int settle, double overshoot, double peakSwing, double residual, double maxTilt, double swing5, double swing10) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "settle %d t, overshoot %.2f b, peak swing %.1f, residual(>=300 t) %.2f, swing at 5 s/10 s after arrival %.2f/%.2f, max tilt %.1f",
                settle, overshoot, peakSwing, residual, swing5, swing10, maxTilt);
        }
    }

    static Step step(double load, double length, double tz) {
        Sim s = new Sim(load, length);
        double tx = 20;
        int settle = -1;
        double over = 0, peak = 0, residual = 0, maxTilt = 0, s5 = 0, s10 = 0;
        for (int t = 1; t <= 900; t++) {
            s.tick(tx, 0, tz);
            double err = Math.sqrt(Math.pow(s.drone.p[0] - tx, 2) + s.drone.p[1] * s.drone.p[1] + Math.pow(s.drone.p[2] - tz, 2));
            over = Math.max(over, s.drone.p[0] - tx);
            peak = Math.max(peak, s.rope.swingDegrees());
            maxTilt = Math.max(maxTilt, s.drone.tilt());
            if (t >= 300) {
                residual = Math.max(residual, s.rope.swingDegrees());
            }
            if (err > 0.5) {
                settle = -1;
            } else if (settle < 0) {
                settle = t;
            }
        }
        if (settle > 0) {
            Sim r = new Sim(load, length);
            for (int t = 1; t <= settle + 177; t++) {
                r.tick(tx, 0, tz);
                if (t >= settle + 100) {
                    s5 = Math.max(s5, t < settle + 177 ? r.rope.swingDegrees() : 0);
                }
            }
            Sim q = new Sim(load, length);
            for (int t = 1; t <= settle + 277; t++) {
                q.tick(tx, 0, tz);
                if (t >= settle + 200) {
                    s10 = Math.max(s10, q.rope.swingDegrees());
                }
            }
        }
        return new Step(settle, over, peak, load > 0 ? residual : 0, maxTilt, s5, s10);
    }
}
