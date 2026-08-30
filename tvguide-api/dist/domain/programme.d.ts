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
}
