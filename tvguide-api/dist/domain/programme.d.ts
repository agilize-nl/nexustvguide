export interface NlzietProgrammeTarget {
    kind: 'replay';
    contentItemId: string;
    assetId: string;
    channelId: string;
    isReplayAllowed: boolean;
    isRestartAllowed: boolean;
}
export interface Programme {
    id: string;
    channelId: string;
    title: string;
    start: string;
    end: string;
    description: string | null;
    imageUrl: string | null;
    genre: string | null;
    isLive: boolean;
    isRerun: boolean;
    isPremiere: boolean;
    ageRating: string | null;
    nlziet?: NlzietProgrammeTarget | null;
    /** @deprecated Niet gebruiken voor een klik op een gidsprogramma. */
    nlzietId?: string | null;
}
