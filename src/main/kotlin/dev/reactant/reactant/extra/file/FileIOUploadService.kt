package dev.reactant.reactant.extra.file

import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.extra.net.BaseUrl
import dev.reactant.reactant.extra.net.RetrofitJsonAPI
import io.reactivex.rxjava3.core.Single
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File

/**
 * A utils for uploading temp file to https://file.io
 * Use only if you trust on it
 */
@Component
class FileIOUploadService(
        private val fileIOAPI: RetrofitJsonAPI<FileIOAPI>
) {
    data class FileIOResponse(val success: Boolean, val key: String, val link: String, val expiry: String)

    @BaseUrl("https://file.io/")
    interface FileIOAPI {
        @Multipart
        @POST("/")
        fun uploadFile(@Part file: MultipartBody.Part): Single<FileIOResponse>
    }

    fun upload(fileName: String, content: String, mediaType: MediaType = "text/plain".toMediaType()): Single<FileIOResponse> {
        return fileIOAPI.service.uploadFile(MultipartBody.Part.createFormData("file", fileName, content.toRequestBody(mediaType)))
    }

    fun upload(fileName: String, content: File, mediaType: MediaType): Single<FileIOResponse> {
        return fileIOAPI.service.uploadFile(MultipartBody.Part.createFormData("file", fileName, content.asRequestBody(mediaType)))
    }
}

