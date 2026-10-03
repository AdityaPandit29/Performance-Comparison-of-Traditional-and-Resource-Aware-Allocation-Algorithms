package org.cloudbus.cloudsim.examples;

import java.text.DecimalFormat;
import java.util.*;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.*;

public class project {

    // ---------------- Requests (R1..R4) ----------------
    static final String[] REQ_NAME = {"R1", "R2", "R3", "R4"};
    static final int[] REQ_CPU = {2, 4, 1, 6};      // cores
    static final int[] REQ_RAM = {4, 2, 8, 4};      // GB
    static final int[] REQ_BW  = {10, 20, 5, 30};   // Mbps

    // ---------------- Hosts (ASSUMED, see report) ----------------
    static final int[] HOST_CPU = {8, 8, 16};       // cores
    static final int[] HOST_RAM = {16, 8, 32};      // GB
    static final int[] HOST_BW  = {50, 40, 100};    // Mbps

    static final int MIPS_PER_CORE = 1000;
    static final DecimalFormat df = new DecimalFormat("0.00");

    static final List<String> summary = new ArrayList<>();

    public static void main(String[] args) {
        runSimulation("RR");
        runSimulation("FIRST-FIT");
        runSimulation("BEST-FIT");
        runSimulation("RESOURCE-AWARE");

        Log.printLine("\n==================== COMPARISON ====================");
        Log.printLine("Algorithm\tHostsUsed\tAvgCPU%\tAvgRAM%\tAvgBW%\tAvgUtil%\tImbalance");
        for (String s : summary) Log.printLine(s);
        Log.printLine("====================================================");
    }

    // =========================================================
    // RUN ONE SIMULATION
    // =========================================================
    private static void runSimulation(String algorithm) {

        Log.printLine("\n==========================================================");
        Log.printLine("              RUNNING " + algorithm);
        Log.printLine("==========================================================");

        try {
            CloudSim.init(1, Calendar.getInstance(), false);

            // 1. Decide placement
            int[] placement = computeMapping(algorithm);   // request -> host (-1 = rejected)

            Map<Integer, Integer> vmToHost = new HashMap<>();
            for (int i = 0; i < placement.length; i++) vmToHost.put(i, placement[i]);

            // 2. Datacenter + Broker
            createDatacenter("Datacenter_0", vmToHost);
            DatacenterBroker broker = new DatacenterBroker("Broker_" + algorithm);
            int brokerId = broker.getId();

            // 3. VMs (one per request)
            List<Vm> vmList = new ArrayList<>();
            for (int i = 0; i < REQ_NAME.length; i++) {
                if (placement[i] < 0) continue;  // rejected request
                vmList.add(new Vm(i, brokerId, MIPS_PER_CORE, REQ_CPU[i],
                        REQ_RAM[i] * 1024, REQ_BW[i], 10000, "Xen",
                        new CloudletSchedulerTimeShared()));
            }
            broker.submitVmList(vmList);

            // 4. One cloudlet per VM
            List<Cloudlet> cloudletList = new ArrayList<>();
            UtilizationModel full = new UtilizationModelFull();
            for (Vm vm : vmList) {
                Cloudlet c = new Cloudlet(vm.getId(), 10000, 1, 300, 300, full, full, full);
                c.setUserId(brokerId);
                c.setVmId(vm.getId());
                cloudletList.add(c);
            }
            broker.submitCloudletList(cloudletList);

            // 5. Simulate
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            printCloudletResults(broker.getCloudletReceivedList(), placement);
            printUtilization(algorithm, placement);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================
    // MAPPING (placement) ALGORITHMS
    // =========================================================
    private static int[] computeMapping(String algorithm) {

        int n = REQ_NAME.length, h = HOST_CPU.length;
        int[] placement = new int[n];
        Arrays.fill(placement, -1);

        // remaining capacity
        int[] cpu = HOST_CPU.clone(), ram = HOST_RAM.clone(), bw = HOST_BW.clone();
        boolean[] active = new boolean[h];

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);

        // Resource-aware: handle requests with largest dominant share first
        if (algorithm.equals("RESOURCE-AWARE")) {
            final double totCpu = sum(HOST_CPU), totRam = sum(HOST_RAM), totBw = sum(HOST_BW);
            order.sort((a, b) -> Double.compare(
                    dominant(b, totCpu, totRam, totBw),
                    dominant(a, totCpu, totRam, totBw)));
        }

        int rrPointer = 0;

        for (int r : order) {
            int chosen = -1;

            switch (algorithm) {

                case "RR":
                    for (int k = 0; k < h; k++) {
                        int cand = (rrPointer + k) % h;
                        if (fits(r, cand, cpu, ram, bw)) { chosen = cand; break; }
                    }
                    if (chosen >= 0) rrPointer = (chosen + 1) % h;
                    break;

                case "FIRST-FIT":
                    for (int cand = 0; cand < h; cand++)
                        if (fits(r, cand, cpu, ram, bw)) { chosen = cand; break; }
                    break;

                case "BEST-FIT": {
                    // host with the LEAST normalised leftover capacity after placement
                    double best = Double.MAX_VALUE;
                    for (int cand = 0; cand < h; cand++) {
                        if (!fits(r, cand, cpu, ram, bw)) continue;
                        double left = (double) (cpu[cand] - REQ_CPU[r]) / HOST_CPU[cand]
                                    + (double) (ram[cand] - REQ_RAM[r]) / HOST_RAM[cand]
                                    + (double) (bw[cand]  - REQ_BW[r])  / HOST_BW[cand];
                        if (left < best) { best = left; chosen = cand; }
                    }
                    break;
                }

                case "RESOURCE-AWARE": {
                    // Prefer already-active hosts (consolidation); among them choose
                    // the one whose CPU/RAM/BW utilisation is most BALANCED afterwards
                    // (lowest variance). If none is active/fits, open the smallest host.
                    double bestScore = Double.MAX_VALUE;
                    for (int cand = 0; cand < h; cand++) {
                        if (!active[cand] || !fits(r, cand, cpu, ram, bw)) continue;
                        double score = imbalanceAfter(r, cand, cpu, ram, bw);
                        if (score < bestScore) { bestScore = score; chosen = cand; }
                    }
                    if (chosen < 0) {
                        double smallest = Double.MAX_VALUE;
                        for (int cand = 0; cand < h; cand++) {
                            if (active[cand] || !fits(r, cand, cpu, ram, bw)) continue;
                            double cap = HOST_CPU[cand] + HOST_RAM[cand] + HOST_BW[cand];
                            if (cap < smallest) { smallest = cap; chosen = cand; }
                        }
                    }
                    break;
                }
            }

            if (chosen >= 0) {
                cpu[chosen] -= REQ_CPU[r];
                ram[chosen] -= REQ_RAM[r];
                bw[chosen]  -= REQ_BW[r];
                active[chosen] = true;
                placement[r] = chosen;
                Log.printLine(String.format("%s: %s -> Host%d", algorithm, REQ_NAME[r], chosen));
            } else {
                Log.printLine(String.format("%s: %s REJECTED (no host fits)", algorithm, REQ_NAME[r]));
            }
        }
        return placement;
    }

    private static boolean fits(int r, int hst, int[] cpu, int[] ram, int[] bw) {
        return cpu[hst] >= REQ_CPU[r] && ram[hst] >= REQ_RAM[r] && bw[hst] >= REQ_BW[r];
    }

    private static double dominant(int r, double tc, double tr, double tb) {
        return Math.max(REQ_CPU[r] / tc, Math.max(REQ_RAM[r] / tr, REQ_BW[r] / tb));
    }

    private static double sum(int[] a) { double s = 0; for (int x : a) s += x; return s; }

    private static double imbalanceAfter(int r, int hst, int[] cpu, int[] ram, int[] bw) {
        double uc = 1.0 - (double) (cpu[hst] - REQ_CPU[r]) / HOST_CPU[hst];
        double ur = 1.0 - (double) (ram[hst] - REQ_RAM[r]) / HOST_RAM[hst];
        double ub = 1.0 - (double) (bw[hst]  - REQ_BW[r])  / HOST_BW[hst];
        return variance(uc, ur, ub);
    }

    private static double variance(double... v) {
        double m = 0; for (double x : v) m += x; m /= v.length;
        double s = 0; for (double x : v) s += (x - m) * (x - m);
        return s / v.length;
    }

    // =========================================================
    // CUSTOM ALLOCATION POLICY: puts each VM on the host chosen above
    // =========================================================
    static class PinnedAllocationPolicy extends VmAllocationPolicySimple {

        private final Map<Integer, Integer> vmToHost;

        PinnedAllocationPolicy(List<? extends Host> list, Map<Integer, Integer> vmToHost) {
            super(list);
            this.vmToHost = vmToHost;
        }

        @Override
        public boolean allocateHostForVm(Vm vm) {
            Integer idx = vmToHost.get(vm.getId());
            if (idx == null || idx < 0) return false;

            Host host = getHostList().get(idx);
            if (host.vmCreate(vm)) {
                getVmTable().put(vm.getUid(), host);
                int pes = vm.getNumberOfPes();
                getUsedPes().put(vm.getUid(), pes);
                int hi = getHostList().indexOf(host);
                getFreePes().set(hi, getFreePes().get(hi) - pes);
                return true;
            }
            return false;
        }
    }

    // =========================================================
    // CREATE DATACENTER
    // =========================================================
    private static Datacenter createDatacenter(String name, Map<Integer, Integer> vmToHost) {

        List<Host> hostList = new ArrayList<>();

        for (int i = 0; i < HOST_CPU.length; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < HOST_CPU[i]; p++)
                peList.add(new Pe(p, new PeProvisionerSimple(MIPS_PER_CORE)));

            hostList.add(new Host(
                    i,
                    new RamProvisionerSimple(HOST_RAM[i] * 1024),
                    new BwProvisionerSimple(HOST_BW[i]),
                    1000000,
                    peList,
                    new VmSchedulerTimeShared(peList)));
        }

        DatacenterCharacteristics ch = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.001, 0.0);

        try {
            return new Datacenter(name, ch,
                    new PinnedAllocationPolicy(hostList, vmToHost),
                    new LinkedList<Storage>(), 0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // =========================================================
    // RESULTS
    // =========================================================
    private static void printCloudletResults(List<Cloudlet> list, int[] placement) {
        Log.printLine("\nCloudlet\tRequest\tVM\tHost\tStatus\tStart\tFinish");
        for (Cloudlet c : list) {
            if (c.getCloudletStatus() == Cloudlet.SUCCESS) {
                Log.printLine(c.getCloudletId() + "\t\t"
                        + REQ_NAME[c.getCloudletId()] + "\t"
                        + c.getVmId() + "\t"
                        + placement[c.getCloudletId()] + "\tSUCCESS\t"
                        + df.format(c.getExecStartTime()) + "\t"
                        + df.format(c.getFinishTime()));
            }
        }
    }

    /**
     * Utilization of a host d = used_d / capacity_d for CPU, RAM, BW.
     * Averages are over ACTIVE hosts (hosts that carry at least one VM).
     * Imbalance = std-dev of per-host average utilization across active hosts.
     */
    private static void printUtilization(String algorithm, int[] placement) {

        int h = HOST_CPU.length;
        int[] uc = new int[h], ur = new int[h], ub = new int[h];
        boolean[] active = new boolean[h];

        for (int i = 0; i < placement.length; i++) {
            if (placement[i] < 0) continue;
            int p = placement[i];
            uc[p] += REQ_CPU[i]; ur[p] += REQ_RAM[i]; ub[p] += REQ_BW[i];
            active[p] = true;
        }

        Log.printLine("\nHost\tCPU%\tRAM%\tBW%\tAvg%");
        double sc = 0, sr = 0, sb = 0;
        int used = 0;
        List<Double> hostAvg = new ArrayList<>();

        for (int i = 0; i < h; i++) {
            if (!active[i]) {
                Log.printLine(i + "\tidle");
                continue;
            }
            double c = 100.0 * uc[i] / HOST_CPU[i];
            double r = 100.0 * ur[i] / HOST_RAM[i];
            double b = 100.0 * ub[i] / HOST_BW[i];
            double a = (c + r + b) / 3;
            Log.printLine(i + "\t" + df.format(c) + "\t" + df.format(r)
                    + "\t" + df.format(b) + "\t" + df.format(a));
            sc += c; sr += r; sb += b; used++;
            hostAvg.add(a);
        }

        if (used == 0) return;

        double avgC = sc / used, avgR = sr / used, avgB = sb / used;
        double avgAll = (avgC + avgR + avgB) / 3;

        double m = 0; for (double x : hostAvg) m += x; m /= used;
        double var = 0; for (double x : hostAvg) var += (x - m) * (x - m);
        double imbalance = Math.sqrt(var / used);

        Log.printLine("\nHosts used           : " + used + " / " + h);
        Log.printLine("Avg CPU utilization  : " + df.format(avgC) + " %");
        Log.printLine("Avg RAM utilization  : " + df.format(avgR) + " %");
        Log.printLine("Avg BW utilization   : " + df.format(avgB) + " %");
        Log.printLine("Overall utilization  : " + df.format(avgAll) + " %");
        Log.printLine("Load imbalance (SD)  : " + df.format(imbalance));

        summary.add(algorithm + "\t" + used + "\t\t" + df.format(avgC) + "\t"
                + df.format(avgR) + "\t" + df.format(avgB) + "\t"
                + df.format(avgAll) + "\t\t" + df.format(imbalance));
    }
}