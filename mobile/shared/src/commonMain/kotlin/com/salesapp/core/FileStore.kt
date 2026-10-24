package com.salesapp.core

// Somewhere on the phone to keep text that must survive the app being closed, bigger than
// KeyValueStore is meant for. An interface so a test can stand in for the phone's files.
interface Files {
    fun read(name: String): String?

    fun write(name: String, text: String)

    fun delete(name: String)
}

// The real one: the app's own private folder. Used for the trip outbox, which holds signatures
// and photos until there is a network.
expect class FileStore() : Files
