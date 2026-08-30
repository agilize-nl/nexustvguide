export const MAX_START_DIFF_MS = 6 * 60 * 1000; // 6 minuten
export const MAX_DURATION_DIFF_MS = 10 * 60 * 1000; // 10 minuten
export function normalizeEpgTitle(title) {
    if (!title)
        return "";
    return title
        .normalize("NFD")
        .replace(/[̀-ͯ]/g, "") // Diakrieten verwijderen
        .toLowerCase()
        .replace(/^(nos|avrotros|bnnvara|kro-ncrv|kro|ncrv|vpro|max|omroep max|eo|npo|rtl\s*\d*|sbs\s*\d*|viaplay|canvas|vrt|powned|wnl|human|veronica|net\s*5|een)\s*([:\-–—]\s*|\s+)/i, "")
        .replace(/\s*[:\-–—]\s*(afl\.?|aflevering|seizoen|season|s\d+|deel|extra|special|live|herhaling|compilatie|serie|film).*$/i, "")
        .replace(/[^a-z0-9]+/g, " ")
        .trim();
}
export class NlzietEpgMatcher {
    /**
     * Zoekt een exact EPG-target voor één programma op basis van NLZIET EPG-items.
     */
    matchProgramme(programme, epgItems, nlzietChannelId) {
        if (!programme.title || !programme.start || !programme.end) {
            return { target: null, isAmbiguous: false, isTimingOrTitleMismatch: true };
        }
        const progStartMs = new Date(programme.start).getTime();
        const progEndMs = new Date(programme.end).getTime();
        const progDurationMs = progEndMs - progStartMs;
        const normProgTitle = normalizeEpgTitle(programme.title);
        if (!normProgTitle) {
            return { target: null, isAmbiguous: false, isTimingOrTitleMismatch: true };
        }
        const candidates = [];
        for (const item of epgItems) {
            if (!item.contentItemId || !item.assetId || !item.startAt || !item.endAt) {
                continue;
            }
            const epgStartMs = new Date(item.startAt).getTime();
            const epgEndMs = new Date(item.endAt).getTime();
            const epgDurationMs = epgEndMs - epgStartMs;
            const startDiffMs = Math.abs(progStartMs - epgStartMs);
            const durationDiffMs = Math.abs(progDurationMs - epgDurationMs);
            if (startDiffMs > MAX_START_DIFF_MS)
                continue;
            if (durationDiffMs > MAX_DURATION_DIFF_MS)
                continue;
            const normEpgTitle = normalizeEpgTitle(item.title);
            const titleMatches = normProgTitle === normEpgTitle ||
                normProgTitle.replace(/ /g, "") === normEpgTitle.replace(/ /g, "");
            if (titleMatches) {
                candidates.push({ content: item, startDiffMs, durationDiffMs });
            }
        }
        if (candidates.length === 1) {
            const best = candidates[0].content;
            const target = {
                kind: "replay",
                contentItemId: best.contentItemId,
                assetId: best.assetId,
                channelId: nlzietChannelId,
                isReplayAllowed: best.isReplayAllowed,
                isRestartAllowed: best.isRestartAllowed,
            };
            return { target, isAmbiguous: false, isTimingOrTitleMismatch: false };
        }
        else if (candidates.length > 1) {
            return { target: null, isAmbiguous: true, isTimingOrTitleMismatch: false };
        }
        else {
            return { target: null, isAmbiguous: false, isTimingOrTitleMismatch: true };
        }
    }
    /**
     * Verrijkt programmas voor een dag met data uit de NLZIET EPG response.
     */
    enrichProgrammes(programmes, epgResponse, channels = [], isOutsideEpgWindow = false, epgFetchFailed = false) {
        const totalProgrammes = programmes.length;
        const channelMap = new Map(channels.map((c) => [c.id, c]));
        if (epgFetchFailed) {
            for (const p of programmes) {
                p.nlziet = null;
                p.nlzietId = null;
            }
            return {
                totalProgrammes,
                epgEligibleProgrammes: 0,
                exactTargets: 0,
                replayAllowedTargets: 0,
                rejectedAmbiguous: 0,
                rejectedTitleOrTiming: 0,
                skippedOutsideEpgWindow: 0,
                epgFetchFailed: true,
                enrichedProgrammes: 0,
                enrichmentRate: 0,
                matchedSlugs: {},
            };
        }
        if (isOutsideEpgWindow || !epgResponse) {
            for (const p of programmes) {
                p.nlziet = null;
                p.nlzietId = null;
            }
            return {
                totalProgrammes,
                epgEligibleProgrammes: 0,
                exactTargets: 0,
                replayAllowedTargets: 0,
                rejectedAmbiguous: 0,
                rejectedTitleOrTiming: 0,
                skippedOutsideEpgWindow: totalProgrammes,
                epgFetchFailed: false,
                enrichedProgrammes: 0,
                enrichmentRate: 0,
                matchedSlugs: {},
            };
        }
        // Indexeer EPG items per NLZIET channel ID
        const epgByChannel = new Map();
        for (const group of epgResponse.data || []) {
            const chId = group.channel?.content?.id;
            if (!chId)
                continue;
            const items = group.programLocations.map((pl) => pl.content);
            epgByChannel.set(chId, items);
        }
        let epgEligible = 0;
        let exactTargets = 0;
        let replayAllowedTargets = 0;
        let rejectedAmbiguous = 0;
        let rejectedTitleOrTiming = 0;
        for (const p of programmes) {
            const ch = channelMap.get(p.channelId);
            const nlzietChId = ch?.nlzietChannelId;
            if (!ch?.inNlziet || !nlzietChId) {
                p.nlziet = null;
                p.nlzietId = null;
                continue;
            }
            epgEligible++;
            const epgItems = epgByChannel.get(nlzietChId) || [];
            const { target, isAmbiguous, isTimingOrTitleMismatch } = this.matchProgramme(p, epgItems, nlzietChId);
            if (target) {
                p.nlziet = target;
                p.nlzietId = null; // Oude legacy nlzietId niet meer vullen voor gidsklik
                exactTargets++;
                if (target.isReplayAllowed) {
                    replayAllowedTargets++;
                }
            }
            else {
                p.nlziet = null;
                p.nlzietId = null;
                if (isAmbiguous) {
                    rejectedAmbiguous++;
                }
                else if (isTimingOrTitleMismatch) {
                    rejectedTitleOrTiming++;
                }
            }
        }
        return {
            totalProgrammes,
            epgEligibleProgrammes: epgEligible,
            exactTargets,
            replayAllowedTargets,
            rejectedAmbiguous,
            rejectedTitleOrTiming,
            skippedOutsideEpgWindow: 0,
            epgFetchFailed: false,
            enrichedProgrammes: exactTargets,
            enrichmentRate: totalProgrammes > 0 ? exactTargets / totalProgrammes : 0,
            matchedSlugs: {},
        };
    }
}
