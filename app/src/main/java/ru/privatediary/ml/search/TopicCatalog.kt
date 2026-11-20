package ru.privatediary.ml.search

import java.security.MessageDigest

/** Fixed bilingual broad domains; descriptors are public application data, never diary text. */
object TopicCatalog {
    data class Topic(val id: String, val russian: List<String>, val english: List<String>)
    enum class Language { RUSSIAN, ENGLISH }
    enum class Prefix { QUERY, PASSAGE }
    data class Prototype(val topicIndex: Int, val language: Language, val prefix: Prefix, val text: String)

    const val SHA256 = "5bf764459cc7898b1451407efbf2e5d0534268d747eaa45efe480941fc1946a4"
    val topics: List<Topic> = listOf(
        Topic("food", listOf("приём пищи", "еда и питание", "приготовление еды"),
            listOf("eating a meal", "food and nutrition", "cooking food")),
        Topic("games", listOf("победа в игре", "компьютерные игры", "настольные игры"),
            listOf("winning a game", "video games", "board games")),
        Topic("health", listOf("лечение болезни", "приём у врача", "состояние здоровья"),
            listOf("treating an illness", "a doctor appointment", "physical health")),
        Topic("work", listOf("работа и обязанности", "рабочие задачи", "карьера"),
            listOf("work duties", "work tasks", "career")),
        Topic("study", listOf("учёба и экзамены", "изучение предмета", "домашнее задание"),
            listOf("studying and exams", "learning a subject", "homework")),
        Topic("family", listOf("семья и родственники", "семейные отношения", "встреча с родителями"),
            listOf("family and relatives", "family relationships", "visiting parents")),
        Topic("friends", listOf("дружба и друзья", "общение с друзьями", "встреча с друзьями"),
            listOf("friendship", "talking with friends", "meeting friends")),
        Topic("love", listOf("любовь и романтические отношения", "свидание", "расставание"),
            listOf("love and romance", "a romantic date", "a breakup")),
        Topic("calm", listOf("душевное спокойствие", "расслабление и умиротворение", "отсутствие тревоги"),
            listOf("inner peace", "relaxation and tranquility", "feeling calm")),
        Topic("stress", listOf("стресс и тревога", "нервное напряжение", "переживания"),
            listOf("stress and anxiety", "feeling tense", "worrying")),
        Topic("joy", listOf("радость и счастье", "хорошее настроение", "приятное событие"),
            listOf("joy and happiness", "feeling happy", "a pleasant event")),
        Topic("sadness", listOf("грусть и печаль", "плохое настроение", "потеря и горе"),
            listOf("sadness", "feeling sad", "grief and loss")),
        Topic("anger", listOf("злость и раздражение", "гнев", "ссора и конфликт"),
            listOf("anger and irritation", "feeling angry", "a conflict")),
        Topic("travel", listOf("путешествие и поездка", "отпуск в другом городе", "поездка на самолёте"),
            listOf("travel and trips", "a vacation abroad", "flying to another city")),
        Topic("sleep", listOf("сон и отдых", "высыпаться", "бессонница"),
            listOf("sleep and rest", "getting enough sleep", "insomnia")),
        Topic("sport", listOf("спорт и тренировка", "физические упражнения", "занятия спортом"),
            listOf("sports and exercise", "physical training", "a workout")),
        Topic("nature", listOf("природа и прогулки", "лес и озеро", "прогулка на свежем воздухе"),
            listOf("nature and walks", "a forest and a lake", "walking outdoors")),
        Topic("money", listOf("деньги и финансы", "покупки и расходы", "оплата счетов"),
            listOf("money and finances", "shopping and spending", "paying bills")),
        Topic("home", listOf("дом и домашние дела", "уборка квартиры", "ремонт жилья"),
            listOf("home and chores", "cleaning an apartment", "home repairs")),
        Topic("culture", listOf("музыка кино и книги", "чтение книги", "просмотр фильма"),
            listOf("music movies and books", "reading a book", "watching a movie")),
    )

    /** Canonical cache order: topic, RU/EN, query/passage prefix, descriptor order. */
    val prototypes: List<Prototype> = topics.flatMapIndexed { index, topic ->
        Language.entries.flatMap { language ->
            val descriptions = if (language == Language.RUSSIAN) topic.russian else topic.english
            Prefix.entries.flatMap { prefix -> descriptions.map { Prototype(index, language, prefix, it) } }
        }
    }

    init {
        // The versioned line representation binds every descriptor, its language and order.
        val canonical = buildString {
            append("private-diary-topics-v1\n")
            for (topic in topics) {
                append(topic.id).append('\n')
                (topic.russian + topic.english).forEach { append(it).append('\n') }
            }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        check(actual == SHA256) { "Topic catalog changed without updating its cache fingerprint" }
        check(topics.size == 20 && topics.map { it.id }.distinct().size == topics.size)
        check(topics.all { it.russian.size == 3 && it.english.size == 3 })
        check(prototypes.size == 240)
    }

    fun languageOf(text: String): Language =
        if (text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC })
            Language.RUSSIAN else Language.ENGLISH
}
